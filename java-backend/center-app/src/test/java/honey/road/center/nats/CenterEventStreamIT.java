package honey.road.center.nats;

import honey.road.center.it.AbstractCenterNatsIT;
import honey.road.contracts.event.Subjects;
import io.nats.client.api.RetentionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@code CENTER_EVT} is a delivery buffer, not an archive (scale design 9.3): an event stays until every reader of the
 * center has acknowledged it — the ingest into {@code events}, notifications, config applied, MP callbacks — and then
 * leaves the broker. The database keeps it; 30 days of read events in the hub took 8 GB of disk and, on a restore,
 * more memory than the stand had.
 */
class CenterEventStreamIT extends AbstractCenterNatsIT {
    @Autowired CenterStreams streams;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void ready() {
        await().atMost(Duration.ofSeconds(10)).until(streams::ready);
    }

    private static long messages() throws Exception {
        try (var c = testClient()) {
            return c.jetStreamManagement().getStreamInfo(Subjects.STREAM_CENTER_EVT).getStreamState().getMsgCount();
        }
    }

    @Test
    void anEventWrittenToTheDatabaseLeavesTheBroker() throws Exception {
        var id = UUID.randomUUID();
        var body = """
                {"event_id":"%s","event_type":"window.opened","branch_id":"es1","aggregate_type":"window",
                 "aggregate_id":"%s","occurred_at":"2026-10-04T08:00:00Z","data":{}}""".formatted(id, UUID.randomUUID());
        try (var c = testClient()) {
            c.jetStream().publish("center.evt.local", body.getBytes(StandardCharsets.UTF_8));
        }

        await().atMost(Duration.ofSeconds(15)).until(() ->
                jdbc.sql("SELECT count(*) FROM events WHERE event_id=:id").param("id", id).query(Long.class).single() == 1);
        await().atMost(Duration.ofSeconds(10)).until(() -> messages() == 0);
    }

    @Test
    void theStreamKeepsWhatIsUnreadAndDuplicatesAreTheDatabasesJob() throws Exception {
        try (var c = testClient()) {
            var config = c.jetStreamManagement().getStreamInfo(Subjects.STREAM_CENTER_EVT).getConfiguration();
            assertThat(config.getRetentionPolicy()).isEqualTo(RetentionPolicy.Interest);
            assertThat(config.getMaxAge()).isEqualTo(Duration.ofDays(30));                // unread that long: abandoned
            assertThat(config.getDuplicateWindow()).isEqualTo(Duration.ofMinutes(2));
        }
    }
}
