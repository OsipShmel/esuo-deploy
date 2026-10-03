package honey.road.center.logs;

import honey.road.center.it.AbstractCenterNatsIT;
import honey.road.center.nats.CenterStreams;
import honey.road.contracts.event.Subjects;
import io.nats.client.api.DiscardPolicy;
import io.nats.client.api.Source;
import io.nats.client.api.StorageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** CENTER_LOG and the branch log source next to the event source (design 5.5). */
class CenterLogStreamsIT extends AbstractCenterNatsIT {
    @Autowired CenterStreams streams;

    @BeforeEach
    void ready() {
        await().atMost(Duration.ofSeconds(10)).until(streams::ready);
    }

    private static List<Source> sources(String stream) throws Exception {
        try (var c = testClient()) {
            return c.jetStreamManagement().getStreamInfo(stream).getConfiguration().getSources();
        }
    }

    private static long count(List<Source> sources, String api) {
        return sources.stream().filter(s -> s.getExternal() != null && api.equals(s.getExternal().getApi())).count();
    }

    @Test
    void centerLogCreated() throws Exception {
        try (var c = testClient()) {
            var config = c.jetStreamManagement().getStreamInfo(Subjects.STREAM_CENTER_LOG).getConfiguration();
            assertThat(config.getSubjects()).containsExactly("center.log.pull.>");
            assertThat(config.getStorageType()).isEqualTo(StorageType.File);
            assertThat(config.getMaxAge()).isEqualTo(Duration.ofDays(7));
            assertThat(config.getMaxBytes()).isEqualTo(2_147_483_648L);
            assertThat(config.getDiscardPolicy()).isEqualTo(DiscardPolicy.Old);
            assertThat(config.getDuplicateWindow()).isEqualTo(Duration.ofHours(1));
        }
    }

    @Test
    void attachAddsBothSources() throws Exception {
        assertThat(streams.attachBranch("t01")).isTrue();

        var api = Subjects.jsApiPrefix(Subjects.branchDomain("t01"));
        assertThat(count(sources(Subjects.STREAM_CENTER_EVT), api)).isEqualTo(1);
        var logSources = sources(Subjects.STREAM_CENTER_LOG);
        assertThat(count(logSources, api)).isEqualTo(1);
        assertThat(logSources).filteredOn(s -> s.getExternal() != null && api.equals(s.getExternal().getApi()))
                .extracting(Source::getName).containsExactly(Subjects.STREAM_BRANCH_LOG);
    }

    @Test
    void attachIsIdempotent() throws Exception {
        assertThat(streams.attachBranch("t02")).isTrue();
        assertThat(streams.attachBranch("t02")).isTrue();

        var api = Subjects.jsApiPrefix(Subjects.branchDomain("t02"));
        assertThat(count(sources(Subjects.STREAM_CENTER_EVT), api)).isEqualTo(1);
        assertThat(count(sources(Subjects.STREAM_CENTER_LOG), api)).isEqualTo(1);
    }

    @Test
    void addsLogSourceToBranchAttachedBeforeLogsExisted() throws Exception {
        assertThat(streams.attachBranch("t03")).isTrue();
        try (var c = testClient()) {                     // an older center attached events only
            var jsm = c.jetStreamManagement();
            var config = jsm.getStreamInfo(Subjects.STREAM_CENTER_LOG).getConfiguration();
            var api = Subjects.jsApiPrefix(Subjects.branchDomain("t03"));
            var kept = config.getSources().stream()
                    .filter(s -> s.getExternal() == null || !api.equals(s.getExternal().getApi())).toList();
            jsm.updateStream(io.nats.client.api.StreamConfiguration.builder(config).sources(kept).build());
        }

        assertThat(streams.attachBranch("t03")).isTrue();
        assertThat(count(sources(Subjects.STREAM_CENTER_LOG), Subjects.jsApiPrefix(Subjects.branchDomain("t03")))).isEqualTo(1);
    }
}
