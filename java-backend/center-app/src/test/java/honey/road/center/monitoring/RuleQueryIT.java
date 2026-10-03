package honey.road.center.monitoring;

import honey.road.center.it.AbstractCenterIT;
import honey.road.center.monitoring.MonitoringConfig.Rule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The monitoring rules in SQL (scale design 5.1): only what fires leaves the database. The scenarios of the former
 * in-memory rules, against Postgres, on a clock years back and in partitions of their own, so other tests' rows are
 * neither seen nor touched.
 */
class RuleQueryIT extends AbstractCenterIT {
    private static final Instant NOW = Instant.parse("2019-06-01T12:30:00Z");
    private static final Duration FRESH = Duration.ofMinutes(2);

    static final Rule CPU = new Rule("sys.cpu", "threshold", "sys.cpu_system", null, ">", 0.9,
            Duration.ofMinutes(5), null, false, "warning", "ПК отделения не справляется: CPU {value:%}% уже {for} мин");
    static final Rule DB_POOL = new Rule("sys.db_pool", "threshold", "sys.db_pool_pending", null, ">", 0.0,
            Duration.ofMinutes(2), null, false, "warning", "Узкое место в базе: запросы ждут соединения");
    static final Rule DISK = new Rule("sys.disk", "ratio", "sys.disk_free_mb", "sys.disk_total_mb", "<", 0.1,
            null, null, false, "critical", "На диске отделения осталось {value:%}%");
    static final Rule LINK = new Rule("sys.link", "increase", "sys.nats_reconnects", null, ">", 5.0,
            null, Duration.ofHours(1), false, "warning", "Нестабильная связь с центром: {value} переподключений за час");
    static final Rule RESTART = new Rule("sys.restart", "decrease", "sys.uptime_sec", null, null, null,
            null, Duration.ofMinutes(30), false, "info", "Приложение отделения перезапускалось в {at}");
    static final Rule CLOCK = new Rule("sys.clock", "threshold", "clock_skew_sec", null, ">", 60.0,
            Duration.ofMinutes(2), null, true, "warning", "Часы отделения расходятся с центром на {value} с");
    static final List<Rule> ALL = List.of(CPU, DB_POOL, DISK, LINK, RESTART, CLOCK);

    @Autowired JdbcClient jdbc;

    @BeforeEach
    void ownPartitions() {
        for (var from : List.of(NOW.minus(Duration.ofMinutes(90)), NOW.minus(Duration.ofMinutes(30)))) {
            var hour = from.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
            var name = "branch_metrics_p" + MetricsPartitions.suffix(hour);
            jdbc.sql("CREATE TABLE IF NOT EXISTS " + name + " PARTITION OF branch_metrics FOR VALUES FROM ('" + hour
                    + "') TO ('" + hour.plus(Duration.ofHours(1)) + "')").update();
            jdbc.sql("TRUNCATE " + name).update();
        }
        jdbc.sql("DELETE FROM branches WHERE id LIKE 'rq%'").update();
    }

    private void point(String branch, long secondsAgo, String column, Object value) {
        points(branch, secondsAgo, secondsAgo, column, value);
    }

    /** One point every 10 s from {@code fromAgo} down to {@code toAgo} seconds before NOW, inclusive. */
    private void points(String branch, long fromAgo, long toAgo, String column, Object value) {
        jdbc.sql("INSERT INTO branch_metrics(branch_id,ts," + column + ") "
                        + "SELECT :b, CAST(:now AS timestamptz) - make_interval(secs => s), :v FROM generate_series(:to, :from, 10) s")
                .param("b", branch).param("now", Timestamp.from(NOW)).param("v", value)
                .param("from", fromAgo).param("to", toAgo).update();
    }

    private void restartPoint(String branch, long secondsAgo, long uptime, long reconnects, Instant startedAt) {
        jdbc.sql("""
                        INSERT INTO branch_metrics(branch_id,ts,uptime_sec,nats_reconnects,started_at)
                        VALUES (:b, :ts, :u, :r, :s)""")
                .param("b", branch).param("ts", Timestamp.from(NOW.minusSeconds(secondsAgo))).param("u", uptime)
                .param("r", reconnects).param("s", Timestamp.from(startedAt)).update();
    }

    private List<RuleQuery.Fired> fired(List<Rule> rules, double scale) {
        return new RuleQuery(jdbc, rules, scale, FRESH).fired(NOW).stream()
                .filter(f -> f.branchId().startsWith("rq")).toList();
    }

    private List<RuleQuery.Fired> fired(Rule rule) {
        return fired(List.of(rule), 1.0);
    }

    private static String text(RuleQuery.Fired f) {
        return MonitoringRules.alert(f.branchId(), f.rule(), f.value(), f.since(), NOW, f.zone()).text();
    }

    @Test
    void thresholdFiresWhenHeldForTheWholeDuration() {
        points("rq1", 360, 0, "cpu_system", 0.95);

        assertThat(fired(CPU)).singleElement().satisfies(f -> {
            assertThat(f.branchId()).isEqualTo("rq1");
            assertThat(f.rule()).isEqualTo(CPU);
            assertThat(f.since()).isEqualTo(NOW.minusSeconds(360));
            assertThat(text(f)).isEqualTo("ПК отделения не справляется: CPU 95% уже 6 мин");
        });
    }

    @Test
    void thresholdDoesNotFireWhenOnePointBreaksIt() {
        points("rq1", 360, 130, "cpu_system", 0.95);
        point("rq1", 120, "cpu_system", 0.5);
        points("rq1", 110, 0, "cpu_system", 0.95);

        assertThat(fired(CPU)).isEmpty();
    }

    @Test
    void thresholdDoesNotFireOnStaleData() {
        points("rq1", 560, 180, "cpu_system", 0.95);

        assertThat(fired(CPU)).isEmpty();
    }

    @Test
    void thresholdDoesNotFireBeforeDuration() {
        points("rq1", 240, 0, "cpu_system", 0.95);

        assertThat(fired(CPU)).isEmpty();
    }

    @Test
    void thresholdDoesNotBridgeAGap() {
        points("rq1", 600, 400, "cpu_system", 0.95);
        points("rq1", 100, 0, "cpu_system", 0.95);                     // 5 minutes without heartbeats in between

        assertThat(fired(CPU)).isEmpty();
    }

    @Test
    void thresholdSkipsPointsWithoutTheMetric() {
        points("rq1", 360, 0, "cpu_system", 0.95);
        points("rq1", 355, 5, "waiting", 3);                           // a heartbeat of a node without sys in between

        assertThat(fired(CPU)).hasSize(1);
    }

    @Test
    void ruleWithAShorterHoldUsesItsOwnWindow() {
        points("rq1", 400, 0, "db_pool_pending", 2);

        assertThat(fired(List.of(CPU, DB_POOL), 1.0)).singleElement()
                .satisfies(f -> assertThat(f.rule()).isEqualTo(DB_POOL));
    }

    @Test
    void ratioUsesTheLastPoint() {
        jdbc.sql("""
                        INSERT INTO branch_metrics(branch_id,ts,disk_free_mb,disk_total_mb)
                        VALUES ('rq1', :a, 30000, 60000), ('rq1', :b, 5000, 60000)""")
                .param("a", Timestamp.from(NOW.minusSeconds(20))).param("b", Timestamp.from(NOW.minusSeconds(10))).update();

        assertThat(fired(DISK)).singleElement().satisfies(f -> {
            assertThat(f.since()).isEqualTo(NOW.minusSeconds(10));
            assertThat(text(f)).isEqualTo("На диске отделения осталось 8%");
        });
    }

    @Test
    void ratioOnAStalePointIsQuiet() {
        jdbc.sql("INSERT INTO branch_metrics(branch_id,ts,disk_free_mb,disk_total_mb) VALUES ('rq1', :a, 5000, 60000)")
                .param("a", Timestamp.from(NOW.minusSeconds(300))).update();

        assertThat(fired(DISK)).isEmpty();
    }

    @Test
    void increaseWithinOneProcess() {
        var started = NOW.minus(Duration.ofHours(5));
        restartPoint("rq1", 1800, 18000, 1, started);
        restartPoint("rq1", 900, 18900, 1, started);
        restartPoint("rq1", 600, 19200, 4, started);
        restartPoint("rq1", 10, 19790, 9, started);

        assertThat(fired(LINK)).singleElement().satisfies(f -> {
            assertThat(f.value()).isEqualTo(8);
            assertThat(f.since()).isEqualTo(NOW.minusSeconds(600));     // the first growth within the hour
            assertThat(text(f)).isEqualTo("Нестабильная связь с центром: 8 переподключений за час");
        });
    }

    @Test
    void increaseSurvivesCounterReset() {
        var before = NOW.minus(Duration.ofHours(1));
        var after = NOW.minusSeconds(25);
        restartPoint("rq1", 50, 3550, 2, before);
        restartPoint("rq1", 40, 3560, 4, before);
        restartPoint("rq1", 30, 3570, 7, before);
        restartPoint("rq1", 20, 5, 0, after);                          // node restarted
        restartPoint("rq1", 10, 15, 2, after);

        assertThat(fired(LINK)).singleElement().satisfies(f -> {
            assertThat(text(f)).isEqualTo("Нестабильная связь с центром: 7 переподключений за час");
            assertThat(f.since()).isEqualTo(NOW.minusSeconds(40));
        });
    }

    @Test
    void increaseBelowTheLimitIsQuiet() {
        var started = NOW.minus(Duration.ofHours(5));
        restartPoint("rq1", 20, 100, 1, started);
        restartPoint("rq1", 10, 110, 3, started);

        assertThat(fired(LINK)).isEmpty();
    }

    @Test
    void restartIsTheNodesOwnStartTime() {
        var before = NOW.minusSeconds(530);
        var after = NOW.minusSeconds(15);
        restartPoint("rq1", 30, 500, 0, before);
        restartPoint("rq1", 20, 510, 0, before);
        restartPoint("rq1", 10, 5, 0, after);

        assertThat(fired(RESTART)).singleElement().satisfies(f -> {
            assertThat(f.since()).isEqualTo(after);
            assertThat(text(f)).isEqualTo("Приложение отделения перезапускалось в 15:29");    // 12:29:45Z in Moscow
        });
    }

    @Test
    void restartTimeIsInTheBranchZone() {
        jdbc.sql("""
                INSERT INTO branches(id,name,timezone,first_seen_at,last_heartbeat_at,last_heartbeat)
                VALUES ('rq-ekb','E','Asia/Yekaterinburg',now(),now(),'{}'::jsonb)""").update();
        restartPoint("rq-ekb", 30, 500, 0, NOW.minusSeconds(530));
        restartPoint("rq-ekb", 10, 5, 0, NOW.minusSeconds(15));

        assertThat(fired(RESTART)).singleElement()
                .satisfies(f -> assertThat(text(f)).isEqualTo("Приложение отделения перезапускалось в 17:29"));
    }

    @Test
    void lateHeartbeatsOfOneProcessAreNotARestart() {          // a loaded center reads them late: ts drifts, start does not
        var started = NOW.minus(Duration.ofHours(2));
        restartPoint("rq1", 600, 6600, 0, started);
        restartPoint("rq1", 10, 6900, 0, started);                     // received 290 s "late" for its uptime

        assertThat(fired(RESTART)).isEmpty();
    }

    @Test
    void onlyPointsAfterARestartAreQuiet() {
        var after = NOW.minusSeconds(300);
        restartPoint("rq1", 200, 100, 0, after);
        restartPoint("rq1", 10, 290, 0, after);

        assertThat(fired(RESTART)).isEmpty();
    }

    @Test
    void absComparesTheModule() {
        points("rq1", 150, 0, "clock_skew_ms", -90_000);

        assertThat(fired(CLOCK)).singleElement()
                .satisfies(f -> assertThat(text(f)).isEqualTo("Часы отделения расходятся с центром на 90 с"));
    }

    @Test
    void demoScaleShortensDurationsAndForIsAtLeastOneMinute() {
        points("rq1", 40, 0, "cpu_system", 0.95);                      // 5 min × 0.1 = 30 s

        assertThat(fired(List.of(CPU), 0.1)).singleElement()
                .satisfies(f -> assertThat(text(f)).endsWith("уже 1 мин"));
    }

    @Test
    void missingSysRaisesNothing() {
        points("rq1", 400, 0, "waiting", 3);

        assertThat(fired(ALL, 1.0)).isEmpty();
    }

    @Test
    void eachBranchOnItsOwn() {
        points("rq-hot", 360, 0, "cpu_system", 0.95);
        points("rq-calm", 360, 0, "cpu_system", 0.2);

        assertThat(fired(CPU)).extracting(RuleQuery.Fired::branchId).containsExactly("rq-hot");
    }

    @Test
    void aWholeNetworkReturnsOnlyWhatFires() {
        jdbc.sql("""
                        INSERT INTO branch_metrics(branch_id,ts,cpu_system,db_pool_pending,disk_free_mb,disk_total_mb,
                                                   clock_skew_ms,uptime_sec,nats_reconnects,started_at)
                        SELECT 'rq-n' || b, CAST(:now AS timestamptz) - make_interval(secs => s), CASE WHEN b <= 100 THEN 0.95 ELSE 0.2 END,
                               0, 150000, 238000, 300, 7200 - s, 0, CAST(:now AS timestamptz) - interval '2 hours'
                        FROM generate_series(1, 20000) b, generate_series(0, 420, 10) s""")
                .param("now", Timestamp.from(NOW)).update();
        jdbc.sql("ANALYZE branch_metrics_p2019060112").update();

        long t0 = System.nanoTime();
        var fired = fired(ALL, 1.0);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertThat(fired).hasSize(100).allSatisfy(f -> assertThat(f.rule()).isEqualTo(CPU));
        assertThat(ms).as("one pass over 20 000 branches × 7 min, ms").isLessThan(5_000);
    }
}
