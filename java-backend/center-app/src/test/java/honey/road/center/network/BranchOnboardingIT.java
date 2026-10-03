package honey.road.center.network;

import honey.road.center.it.AbstractCenterNatsIT;
import honey.road.center.nats.CenterStreams;
import honey.road.contracts.event.Subjects;
import io.nats.client.api.Source;
import io.nats.client.impl.NatsMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * New branches in a wave (final plan, C1/C3b): the NATS thread only hands first heartbeats over, the onboarding worker
 * registers them in batches, sources their events — the branch's own {@code BRANCH_EVT} or, with {@code events_via},
 * the region's {@code REGION_EVT} once per region — and publishes their first config. A branch the center already has
 * (a restart) is not re-registered: its heartbeat waits for the flush like any other.
 */
@TestPropertySource(properties = "esuo.center.metrics.flush-interval=1h")
class BranchOnboardingIT extends AbstractCenterNatsIT {
    @Autowired HeartbeatListener listener;
    @Autowired HeartbeatFlush flush;
    @Autowired BranchOnboarding onboarding;
    @Autowired CenterStreams streams;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void ready() {
        await().atMost(Duration.ofSeconds(10)).until(streams::ready);
    }

    private void beat(String branch, String body) {
        listener.handle(NatsMessage.builder().subject(Subjects.heartbeat(branch))
                .data(body.getBytes(StandardCharsets.UTF_8)).build());
    }

    private static String hb(String name, String eventsVia) {
        return "{\"name\":\"" + name + "\",\"timezone\":\"Asia/Yekaterinburg\",\"waiting\":0"
                + (eventsVia == null ? "" : ",\"events_via\":\"" + eventsVia + "\"") + "}";
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private List<Source> centerEvtSources() throws Exception {
        try (var c = testClient()) {
            return c.jetStreamManagement().getStreamInfo(Subjects.STREAM_CENTER_EVT).getConfiguration().getSources();
        }
    }

    private long sourcesWithApi(String api) throws Exception {
        return centerEvtSources().stream().filter(s -> s.getExternal() != null && api.equals(s.getExternal().getApi())).count();
    }

    @Test
    void aWaveOfRegionalBranchesIsRegisteredInBatchesWithOneSourcePerRegion() throws Exception {
        for (int i = 1; i <= 1000; i++) {
            beat("w" + (i % 2 + 1) + "-" + i, hb("Wave " + i, "region-" + (i % 2 + 1)));
        }
        assertThat(onboarding.awaitIdle(Duration.ofSeconds(60))).isTrue();

        assertThat(count("SELECT count(*) FROM branches WHERE id LIKE 'w_-%'")).isEqualTo(1000);
        assertThat(count("SELECT count(*) FROM branches WHERE id LIKE 'w_-%' AND source_attached")).isEqualTo(1000);
        assertThat(count("SELECT count(*) FROM branches WHERE id LIKE 'w_-%' AND config_published_version IS NOT NULL"))
                .isEqualTo(1000);                                     // first config for every new branch, once
        assertThat(count("SELECT count(*) FROM config_versions WHERE branch_id LIKE 'w_-%'")).isEqualTo(1000);
        assertThat(sourcesWithApi(Subjects.jsApiPrefix("region-1"))).isEqualTo(1);
        assertThat(sourcesWithApi(Subjects.jsApiPrefix("region-2"))).isEqualTo(1);
        assertThat(centerEvtSources()).noneMatch(s -> s.getExternal() != null
                && s.getExternal().getApi().startsWith("$JS.branch-w"));   // no BRANCH_EVT per regional branch
    }

    @Test
    void aBranchWithItsOwnLeafGetsItsOwnSource() throws Exception {
        beat("own1", hb("Own", null));
        assertThat(onboarding.awaitIdle(Duration.ofSeconds(10))).isTrue();

        assertThat(count("SELECT count(*) FROM branches WHERE id='own1' AND source_attached")).isEqualTo(1);
        assertThat(sourcesWithApi(Subjects.jsApiPrefix(Subjects.branchDomain("own1")))).isEqualTo(1);
    }

    @Test
    void aBranchKnownBeforeTheRestartIsNotRegisteredAgain() {
        var firstSeen = Instant.parse("2026-01-01T00:00:00Z");
        jdbc.sql("""
                INSERT INTO branches(id,name,timezone,first_seen_at,last_heartbeat_at,last_heartbeat,source_attached)
                VALUES ('old1','Old','Europe/Moscow',:t,:t,'{}'::jsonb,true)""").param("t", Timestamp.from(firstSeen)).update();

        beat("old1", hb("New", null));
        assertThat(onboarding.awaitIdle(Duration.ofSeconds(10))).isTrue();

        assertThat(count("SELECT count(*) FROM config_versions WHERE branch_id='old1'")).isZero();   // no second "first" config
        flush.run();
        assertThat(jdbc.sql("SELECT name FROM branches WHERE id='old1'").query(String.class).single()).isEqualTo("New");
        assertThat(jdbc.sql("SELECT first_seen_at FROM branches WHERE id='old1'").query(Timestamp.class).single().toInstant())
                .isEqualTo(firstSeen);
    }

    @Test
    void laterHeartbeatsOfARegisteredBranchGoToTheBuffer() {
        beat("buf1", hb("First", null));
        assertThat(onboarding.awaitIdle(Duration.ofSeconds(10))).isTrue();
        beat("buf1", hb("Second", null));

        assertThat(onboarding.pending("buf1")).isFalse();
        assertThat(jdbc.sql("SELECT name FROM branches WHERE id='buf1'").query(String.class).single()).isEqualTo("First");
        flush.run();
        assertThat(jdbc.sql("SELECT name FROM branches WHERE id='buf1'").query(String.class).single()).isEqualTo("Second");
    }
}
