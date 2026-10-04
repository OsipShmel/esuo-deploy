package honey.road.center.nats;

import honey.road.center.it.AbstractCenterNatsIT;
import honey.road.center.network.HeartbeatListener;
import honey.road.contracts.event.Subjects;
import io.nats.client.api.External;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.Source;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@code CENTER_EVT} of an older center kept every event for 30 days with a one-hour duplicate window; the center
 * brings it to "until read" when it connects (scale design 9.3). A stream is never left without its reader: with
 * "until read" an event that arrives before any consumer exists is dropped at once, so the ingest consumer comes with
 * the stream, before any source can feed it.
 */
class CenterEventStreamUpgradeIT extends AbstractCenterNatsIT {
    @Autowired CenterStreams streams;
    @Autowired HeartbeatListener heartbeats;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void ready() {
        await().atMost(Duration.ofSeconds(10)).until(streams::ready);
    }

    @Test
    void anOlderStreamKeepingEverythingIsBroughtToUntilRead() throws Exception {
        try (var c = testClient()) {
            var jsm = c.jetStreamManagement();
            jsm.deleteStream(Subjects.STREAM_CENTER_EVT);
            jsm.addStream(StreamConfiguration.builder().name(Subjects.STREAM_CENTER_EVT).subjects("center.evt.local")
                    .storageType(StorageType.File).maxAge(Duration.ofDays(30)).duplicateWindow(Duration.ofHours(1)).build());

            streams.ensure(c);

            var config = jsm.getStreamInfo(Subjects.STREAM_CENTER_EVT).getConfiguration();
            assertThat(config.getRetentionPolicy()).isEqualTo(RetentionPolicy.Interest);
            assertThat(config.getDuplicateWindow()).isEqualTo(Duration.ofMinutes(2));
        }
    }

    @Test
    void aStreamComesWithItsReader() throws Exception {
        try (var c = testClient()) {
            var jsm = c.jetStreamManagement();
            jsm.deleteStream(Subjects.STREAM_CENTER_EVT);

            streams.ensure(c);

            assertThat(jsm.getConsumerNames(Subjects.STREAM_CENTER_EVT)).contains(CenterStreams.INGEST_CONSUMER);
        }
    }

    /** Sources an older center attached have no start of their own; the center gives them number 1 when it connects. */
    @Test
    void sourcesOfAnOlderCenterAreMadeToReadFromNumberOne() throws Exception {
        try (var c = testClient()) {
            var jsm = c.jetStreamManagement();
            var current = jsm.getStreamInfo(Subjects.STREAM_CENTER_EVT).getConfiguration();
            jsm.updateStream(StreamConfiguration.builder(current).addSources(Source.builder().name(Subjects.STREAM_BRANCH_EVT)
                    .external(External.builder().api(Subjects.jsApiPrefix(Subjects.branchDomain("uo1"))).build()).build()).build());

            streams.ensure(c);

            assertThat(jsm.getStreamInfo(Subjects.STREAM_CENTER_EVT).getConfiguration().getSources())
                    .extracting(Source::getStartSeq).containsOnly(1L);
        }
    }

    private static boolean sourced(String api) throws Exception {
        try (var c = testClient()) {
            return c.jetStreamManagement().getStreamInfo(Subjects.STREAM_CENTER_EVT).getConfiguration().getSources().stream()
                    .anyMatch(s -> s.getExternal() != null && api.equals(s.getExternal().getApi()));
        }
    }

    private void attachedBranch(String id, String eventsVia) {
        jdbc.sql("""
                INSERT INTO branches(id,name,timezone,first_seen_at,last_heartbeat_at,last_heartbeat,source_attached)
                VALUES (:id,'U','Europe/Moscow',now(),now(),CAST(:hb AS jsonb),true) ON CONFLICT (id) DO NOTHING""")
                .param("id", id).param("hb", eventsVia == null ? "{}" : "{\"events_via\":\"" + eventsVia + "\"}").update();
    }

    /**
     * The registry remembers which branches are attached; a stream created anew (a lost hub volume, a reset) has no
     * sources at all. Their events are not lost — they wait in the branch and regional buffers — but they reach the
     * center only once the sources are back: the next reconciliation attaches them again.
     */
    @Test
    void branchesAndRegionsAreAttachedAgainToAStreamCreatedAnew() throws Exception {
        attachedBranch("ua1", null);
        attachedBranch("ua2", "region-7");
        assertThat(streams.attachBranch("ua1")).isTrue();
        assertThat(streams.attachRegion("region-7")).isTrue();
        try (var c = testClient()) {
            c.jetStreamManagement().deleteStream(Subjects.STREAM_CENTER_EVT);
            streams.ensure(c);
        }

        heartbeats.reconcile();

        assertThat(sourced(Subjects.jsApiPrefix(Subjects.branchDomain("ua1")))).isTrue();
        assertThat(sourced(Subjects.jsApiPrefix("region-7"))).isTrue();
    }
}
