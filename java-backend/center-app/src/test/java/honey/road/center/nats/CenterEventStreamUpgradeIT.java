package honey.road.center.nats;

import honey.road.center.it.AbstractCenterNatsIT;
import honey.road.contracts.event.Subjects;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
}
