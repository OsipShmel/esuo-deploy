package honey.road.node.messaging.nats;

import honey.road.contracts.event.Subjects;
import honey.road.node.it.AbstractNatsNodeIT;
import io.nats.client.api.DiscardPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.impl.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** {@code BRANCH_LOG} in the branch domain (design 5.3): buffer of important log lines while the center is away. */
class BranchLogStreamIT extends AbstractNatsNodeIT {
    @Autowired BranchStreams streams;
    @Autowired NatsGateway nats;

    @BeforeEach
    void ready() {
        await().atMost(Duration.ofSeconds(10)).until(streams::branchLogReady);
    }

    @Test
    void streamExistsWithSpecLimits() throws Exception {
        try (var c = testClient()) {
            var config = c.jetStreamManagement().getStreamInfo(Subjects.STREAM_BRANCH_LOG).getConfiguration();
            assertThat(config.getSubjects()).containsExactly("branch.b01.log.>");
            assertThat(config.getStorageType()).isEqualTo(StorageType.File);
            assertThat(config.getMaxAge()).isEqualTo(Duration.ofDays(7));
            assertThat(config.getMaxBytes()).isEqualTo(268_435_456L);
            assertThat(config.getDiscardPolicy()).isEqualTo(DiscardPolicy.Old);
            assertThat(config.getDuplicateWindow()).isEqualTo(Duration.ofHours(1));
        }
    }

    @Test
    void ensureIsIdempotent() throws Exception {
        var connection = nats.connection().orElseThrow();
        streams.ensure(connection);
        streams.ensure(connection);
        assertThat(streams.branchLogReady()).isTrue();
        try (var c = testClient()) {
            assertThat(c.jetStreamManagement().getStreamNames()).containsOnlyOnce(Subjects.STREAM_BRANCH_LOG);
        }
    }

    @Test
    void sameMessageIdIsStoredOnce() throws Exception {
        try (var c = testClient()) {
            var js = c.jetStream();
            var subject = Subjects.branchLog("b01", "error");
            var body = "{\"message\":\"dedup\"}".getBytes(StandardCharsets.UTF_8);
            long before = c.jetStreamManagement().getStreamInfo(Subjects.STREAM_BRANCH_LOG).getStreamState().getMsgCount();
            js.publish(subject, new Headers().add("Nats-Msg-Id", "line-1"), body);
            js.publish(subject, new Headers().add("Nats-Msg-Id", "line-1"), body);
            long after = c.jetStreamManagement().getStreamInfo(Subjects.STREAM_BRANCH_LOG).getStreamState().getMsgCount();
            assertThat(after - before).isEqualTo(1);
        }
    }
}
