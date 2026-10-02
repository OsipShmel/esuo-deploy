package honey.road.center.network;

import honey.road.center.it.AbstractCenterIT;
import honey.road.contracts.event.Subjects;
import io.nats.client.impl.NatsMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Heartbeats in batches (design 6.2): a new branch is registered on the spot, a known one waits in memory and is
 * written with the next flush together with its metrics row. Flushes here are explicit: the schedule is an hour.
 */
@TestPropertySource(properties = "esuo.center.metrics.flush-interval=1h")
class HeartbeatFlushIT extends AbstractCenterIT {
    @Autowired HeartbeatListener listener;
    @Autowired HeartbeatFlush flush;
    @Autowired JdbcClient jdbc;

    private void beat(String branch, String body) {
        listener.handle(NatsMessage.builder().subject(Subjects.heartbeat(branch))
                .data(body.getBytes(StandardCharsets.UTF_8)).build());
    }

    private static String hb(String name, int waiting, String sys) {
        return "{\"name\":\"" + name + "\",\"timezone\":\"Europe/Moscow\",\"waiting\":" + waiting
                + (sys == null ? "" : ",\"sys\":" + sys) + "}";
    }

    private String name(String branch) {
        return jdbc.sql("SELECT name FROM branches WHERE id=:id").param("id", branch).query(String.class).single();
    }

    private long metricRows(String branch) {
        return jdbc.sql("SELECT count(*) FROM branch_metrics WHERE branch_id=:id").param("id", branch).query(Long.class).single();
    }

    @Test
    void newBranchIsRegisteredRightAway() {
        beat("hf1", hb("First", 0, null));

        assertThat(name("hf1")).isEqualTo("First");
    }

    @Test
    void knownBranchIsBufferedUntilFlush() {
        beat("hf2", hb("First", 0, null));
        beat("hf2", hb("Second", 5, "{\"cpu_count\":4}"));

        assertThat(name("hf2")).isEqualTo("First");
        assertThat(metricRows("hf2")).isZero();

        flush.run();

        assertThat(name("hf2")).isEqualTo("Second");
        assertThat(jdbc.sql("SELECT (last_heartbeat->>'waiting')::int FROM branches WHERE id='hf2'").query(Integer.class).single())
                .isEqualTo(5);
        assertThat(metricRows("hf2")).isEqualTo(1);
    }

    @Test
    void knownBranchAfterRestartIsBuffered() {
        var firstSeen = Instant.parse("2026-01-01T00:00:00Z");
        jdbc.sql("""
                INSERT INTO branches(id,name,timezone,first_seen_at,last_heartbeat_at,last_heartbeat,source_attached)
                VALUES ('hf3','Old','Europe/Moscow',:t,:t,'{}'::jsonb,true)""").param("t", Timestamp.from(firstSeen)).update();

        beat("hf3", hb("New", 0, null));                                // the listener has never seen hf3 (a restart)

        assertThat(name("hf3")).isEqualTo("Old");
        flush.run();
        assertThat(name("hf3")).isEqualTo("New");
        assertThat(jdbc.sql("SELECT first_seen_at FROM branches WHERE id='hf3'").query(Timestamp.class).single().toInstant())
                .isEqualTo(firstSeen);
        assertThat(jdbc.sql("SELECT source_attached FROM branches WHERE id='hf3'").query(Boolean.class).single()).isTrue();
    }

    @Test
    void heartbeatWithoutSysIsStored() {
        beat("hf4", hb("B", 0, null));
        beat("hf4", hb("B", 1, null));
        flush.run();

        assertThat(jdbc.sql("SELECT count(*) FROM branch_metrics WHERE branch_id='hf4' AND sys IS NULL AND clock_skew_ms IS NULL")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void onlineIsJudgedByReceiptTime() throws Exception {
        beat("hf5", hb("B", 0, null));
        var before = Instant.now();
        beat("hf5", hb("B", 1, null));
        var after = Instant.now();
        Thread.sleep(1200);

        flush.run();

        var at = jdbc.sql("SELECT last_heartbeat_at FROM branches WHERE id='hf5'").query(Timestamp.class).single().toInstant();
        assertThat(at).isBetween(before.minusMillis(1), after.plusMillis(1));
    }

    @Test
    void metricRowsFollowTheResolution() {
        beat("hf6", hb("B", 0, null));
        beat("hf6", hb("B", 1, null));
        flush.run();
        beat("hf6", hb("B", 2, null));                                  // right after the previous one: < resolution
        flush.run();

        assertThat(metricRows("hf6")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT (last_heartbeat->>'waiting')::int FROM branches WHERE id='hf6'").query(Integer.class).single())
                .isEqualTo(2);                                          // the overview still gets every heartbeat
    }
}
