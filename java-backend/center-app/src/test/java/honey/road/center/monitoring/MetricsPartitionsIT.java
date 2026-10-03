package honey.road.center.monitoring;

import honey.road.center.it.AbstractCenterIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.unit.DataSize;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hourly partitions of {@code branch_metrics} (scale design 5.1): created a day ahead, stray rows of the default
 * partition moved in, old hours dropped whole, and a size cap that drops the oldest hours early. Runs on a clock years
 * back: the partitions holding other tests' rows are "ahead" of it and never dropped.
 */
class MetricsPartitionsIT extends AbstractCenterIT {
    private static final Instant NOW = Instant.parse("2020-05-10T12:30:00Z");

    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;

    private MetricsPartitions partitions(Duration retention, DataSize maxSize) {
        return new MetricsPartitions(jdbc, transactions, retention, maxSize, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @BeforeEach
    void clean() {
        for (var name : jdbc.sql("""
                        SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                        WHERE i.inhparent = 'branch_metrics'::regclass AND c.relname LIKE 'branch_metrics_p2020%'""")
                .query(String.class).list()) {
            jdbc.sql("DROP TABLE " + name).update();
        }
        jdbc.sql("DELETE FROM branch_metrics WHERE ts < '2021-01-01'").update();
    }

    private void row(String branch, Instant ts) {
        jdbc.sql("INSERT INTO branch_metrics(branch_id,ts,cpu_system) VALUES (:b,:ts,0.5)")
                .param("b", branch).param("ts", Timestamp.from(ts)).update();
    }

    private String partitionOf(String branch, Instant ts) {
        return jdbc.sql("SELECT tableoid::regclass::text FROM branch_metrics WHERE branch_id=:b AND ts=:ts")
                .param("b", branch).param("ts", Timestamp.from(ts)).query(String.class).single();
    }

    private boolean exists(String partition) {
        return jdbc.sql("SELECT to_regclass(:n) IS NOT NULL").param("n", partition).query(Boolean.class).single();
    }

    @Test
    void createsTheCurrentHourAndADayAhead() {
        partitions(Duration.ofHours(48), DataSize.ofGigabytes(100)).run();

        assertThat(exists("branch_metrics_p2020051012")).isTrue();
        assertThat(exists("branch_metrics_p2020051112")).isTrue();     // +24 h
        row("pm1", NOW);
        assertThat(partitionOf("pm1", NOW)).isEqualTo("branch_metrics_p2020051012");
    }

    @Test
    void strayRowsOfTheDefaultPartitionAreMovedIn() {
        var ts = NOW.plus(Duration.ofHours(3));
        row("pm2", ts);                                             // no partition for that hour yet
        assertThat(partitionOf("pm2", ts)).isEqualTo("branch_metrics_default");

        partitions(Duration.ofHours(48), DataSize.ofGigabytes(100)).run();

        assertThat(partitionOf("pm2", ts)).isEqualTo("branch_metrics_p2020051015");
    }

    @Test
    void hoursOlderThanTheRetentionAreDropped() {
        var p = partitions(Duration.ofHours(48), DataSize.ofGigabytes(100));
        jdbc.sql("""
                CREATE TABLE branch_metrics_p2020050811 PARTITION OF branch_metrics
                FOR VALUES FROM ('2020-05-08 11:00:00+00') TO ('2020-05-08 12:00:00+00')""").update();
        jdbc.sql("""
                CREATE TABLE branch_metrics_p2020050813 PARTITION OF branch_metrics
                FOR VALUES FROM ('2020-05-08 13:00:00+00') TO ('2020-05-08 14:00:00+00')""").update();
        row("pm3", Instant.parse("2020-05-08T10:00:00Z"));             // no partition: in the default one

        p.run();

        assertThat(exists("branch_metrics_p2020050811")).isFalse();     // ended 48.5 h ago
        assertThat(exists("branch_metrics_p2020050813")).isTrue();      // ends 46.5 h ago
        assertThat(jdbc.sql("SELECT count(*) FROM branch_metrics WHERE branch_id='pm3'").query(Long.class).single()).isZero();
    }

    @Test
    void overTheSizeCapTheOldestHoursGoFirstButNeverTheLastHour() {
        var p = partitions(Duration.ofHours(48), DataSize.ofGigabytes(100));
        p.run();
        for (int h = 6; h >= 1; h--) {
            var hour = NOW.truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(h));
            jdbc.sql("CREATE TABLE IF NOT EXISTS branch_metrics_p" + MetricsPartitions.suffix(hour)
                    + " PARTITION OF branch_metrics FOR VALUES FROM ('" + hour + "') TO ('" + hour.plus(Duration.ofHours(1)) + "')")
                    .update();
            jdbc.sql("""
                    INSERT INTO branch_metrics(branch_id,ts,cpu_system)
                    SELECT 'pm4-' || g, :ts, 0.5 FROM generate_series(1, 2000) g""")
                    .param("ts", Timestamp.from(hour.plusSeconds(60))).update();
        }

        partitions(Duration.ofHours(48), DataSize.ofBytes(1)).run();

        assertThat(exists("branch_metrics_p2020051006")).isFalse();
        assertThat(exists("branch_metrics_p2020051010")).isFalse();
        assertThat(exists("branch_metrics_p2020051011")).isTrue();      // the rules look an hour back
        assertThat(exists("branch_metrics_p2020051012")).isTrue();      // the current hour
        assertThat(exists("branch_metrics_p2020051013")).isTrue();      // ahead, empty
    }
}
