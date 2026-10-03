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
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Heartbeats in batches (design 6.2): a new branch is registered by {@link BranchOnboarding} (off the NATS thread,
 * see {@link #register}), a known one waits in memory and is written with the next flush together with its metrics
 * row. Flushes here are explicit: the schedule is an hour.
 */
@TestPropertySource(properties = {"esuo.center.metrics.flush-interval=1h", "esuo.center.metrics.resolution=1s",
        "esuo.center.metrics.resolution-regional=1h", "esuo.center.metrics.flush-chunk=2"})
class HeartbeatFlushIT extends AbstractCenterIT {
    @Autowired HeartbeatListener listener;
    @Autowired HeartbeatFlush flush;
    @Autowired JdbcClient jdbc;
    @Autowired BranchOnboarding onboarding;
    @Autowired HeartbeatBuffer buffer;

    @org.junit.jupiter.api.AfterEach
    void emptyBuffer() {                                                // a failed test must not leave a poison behind
        buffer.drain();
    }

    private void beat(String branch, String body) {
        listener.handle(NatsMessage.builder().subject(Subjects.heartbeat(branch))
                .data(body.getBytes(StandardCharsets.UTF_8)).build());
    }

    /** First heartbeat of a branch: waits until the onboarding worker has registered it (or found it registered). */
    private void register(String branch, String body) {
        beat(branch, body);
        assertThat(onboarding.awaitIdle(Duration.ofSeconds(10))).isTrue();
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
        register("hf1", hb("First", 0, null));

        assertThat(name("hf1")).isEqualTo("First");
    }

    @Test
    void knownBranchIsBufferedUntilFlush() {
        register("hf2", hb("First", 0, null));
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

        register("hf3", hb("New", 0, null));                                // the listener has never seen hf3 (a restart)

        assertThat(name("hf3")).isEqualTo("Old");
        flush.run();
        assertThat(name("hf3")).isEqualTo("New");
        assertThat(jdbc.sql("SELECT first_seen_at FROM branches WHERE id='hf3'").query(Timestamp.class).single().toInstant())
                .isEqualTo(firstSeen);
        assertThat(jdbc.sql("SELECT source_attached FROM branches WHERE id='hf3'").query(Boolean.class).single()).isTrue();
    }

    @Test
    void heartbeatWithoutSysIsStored() {
        register("hf4", hb("B", 0, null));
        beat("hf4", hb("B", 1, null));
        flush.run();

        assertThat(jdbc.sql("SELECT count(*) FROM branch_metrics WHERE branch_id='hf4' AND waiting = 1 AND cpu_count IS NULL AND clock_skew_ms IS NULL")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aFlushLargerThanAChunkIsWrittenWhole() {                    // chunks of 2 here: memory is bounded by a chunk
        for (int i = 1; i <= 5; i++) {
            register("hc" + i, hb("Before", 0, null));
        }
        for (int i = 1; i <= 5; i++) {
            beat("hc" + i, hb("After", i, "{\"cpu_count\":4}"));
        }

        flush.run();

        for (int i = 1; i <= 5; i++) {
            assertThat(name("hc" + i)).isEqualTo("After");
            assertThat(metricRows("hc" + i)).isEqualTo(1);
        }
    }

    @Test
    void aBrokenChunkDoesNotHoldBackTheOthers() {
        for (int i = 1; i <= 4; i++) {
            register("hp" + i, hb("Before", 0, null));
        }
        for (int i = 1; i <= 4; i++) {
            beat("hp" + i, hb("After", i, null));
        }
        buffer.put(new Heartbeat("hp-poison", "P", "Europe/Moscow", "{not json", Instant.now()));   // its chunk fails

        flush.run();

        long written = java.util.stream.IntStream.rangeClosed(1, 4).filter(i -> "After".equals(name("hp" + i))).count();
        assertThat(written).isGreaterThanOrEqualTo(3);                  // all but the poison's chunk mate
        assertThat(buffer.drain()).containsKey("hp-poison");             // kept for the next flush, not dropped
    }

    @Test
    void branchBehindARegionalHubHasItsOwnResolution() throws Exception {
        register("hf8", hb("Own leaf", 0, null));
        var regional = "{\"name\":\"Swarm\",\"timezone\":\"Europe/Moscow\",\"waiting\":0,\"events_via\":\"region-1\"}";
        register("hf9", regional);
        for (int i = 0; i < 2; i++) {
            beat("hf8", hb("Own leaf", i, null));
            beat("hf9", regional);
            flush.run();
            Thread.sleep(1100);                                         // past the 1 s resolution of an ordinary branch
        }

        assertThat(metricRows("hf8")).isEqualTo(2);
        assertThat(metricRows("hf9")).isEqualTo(1);                    // 1 h for the regional ones here
    }

    @Test
    void brokenMetricsWriteDoesNotLoseTheHeartbeat() {
        register("hf7", hb("Before", 0, null));
        beat("hf7", hb("After", 3, "{\"cpu_count\":4}"));
        jdbc.sql("ALTER TABLE branch_metrics RENAME TO branch_metrics_away").update();
        try {
            flush.run();
        } finally {
            jdbc.sql("ALTER TABLE branch_metrics_away RENAME TO branch_metrics").update();
        }

        assertThat(name("hf7")).isEqualTo("After");                    // the branch stays online, its metrics row is lost
        assertThat(metricRows("hf7")).isZero();
    }

    @Test
    void onlineIsJudgedByReceiptTime() throws Exception {
        register("hf5", hb("B", 0, null));
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
        register("hf6", hb("B", 0, null));
        beat("hf6", hb("B", 1, null));
        flush.run();
        beat("hf6", hb("B", 2, null));                                  // right after the previous one: < resolution
        flush.run();

        assertThat(metricRows("hf6")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT (last_heartbeat->>'waiting')::int FROM branches WHERE id='hf6'").query(Integer.class).single())
                .isEqualTo(2);                                          // the overview still gets every heartbeat
    }
}
