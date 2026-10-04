package honey.road.center.nats;

import honey.road.contracts.event.Subjects;
import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.api.Source;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Regions come up while their branches already publish: a fresh center under a running swarm attaches the regional
 * {@code REGION_EVT} on the first heartbeats, and branches keep being attached while events flow. Every event the
 * region has stored reaches the center, from number 1 of its stream.
 */
class RegionsAttachedIT extends AbstractRegionsIT {
    static final int EVENTS = 300;

    @BeforeEach
    void freshStreams() throws Exception {
        try (var hub = connect(HUB); var r1 = connect(REGION_1); var r2 = connect(REGION_2)) {
            hub.jetStreamManagement().deleteStream(Subjects.STREAM_CENTER_EVT);
            streams.ensure(hub);                                       // created anew: no sources, regions forgotten
            for (var region : new Connection[]{r1, r2}) {
                try {
                    region.jetStreamManagement().deleteStream(Subjects.STREAM_REGION_EVT);
                } catch (JetStreamApiException absent) {
                    // first test of the class on this leaf
                }
                createRegionStream(region);
            }
        }
    }

    /**
     * A source without a position of its own starts, by a rule of the server, at the time of the last message of
     * {@code CENTER_EVT} once that stream has dropped anything — and an until-read stream drops all it delivers. A
     * fresh center under a swarm lost the first 6–20 events of the regions attached after the first one; a branch
     * that worked before its first link would lose what it had stored. A source reads its origin from number 1; the
     * database drops what it already has.
     */
    @Test
    void sourcesReadTheirOriginFromNumberOne() throws Exception {
        assertThat(streams.attachRegion("region-1")).isTrue();
        assertThat(streams.attachBranch("rs1")).isTrue();

        try (var hub = connect(HUB)) {
            var jsm = hub.jetStreamManagement();
            for (var stream : new String[]{Subjects.STREAM_CENTER_EVT, Subjects.STREAM_CENTER_LOG}) {
                assertThat(jsm.getStreamInfo(stream).getConfiguration().getSources())
                        .as(stream).isNotEmpty().extracting(Source::getStartSeq).containsOnly(1L);
            }
        }
    }

    @Test
    void attachingBranchesWhileRegionsFlowLosesNothing() throws Exception {
        assertThat(streams.attachRegion("region-1")).isTrue();
        assertThat(streams.attachRegion("region-2")).isTrue();
        var started = new CountDownLatch(1);
        Future<?> traffic = Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
            try (var r1 = connect(REGION_1); var r2 = connect(REGION_2)) {
                for (int i = 0; i < EVENTS; i++) {
                    publish(r1, "rc1");
                    publish(r2, "rd1");
                    if (i == 50) started.countDown();
                    Thread.sleep(3);
                }
            }
            return null;
        });
        started.await();

        for (int i = 0; i < 5; i++) {
            streams.attachBranch("ry" + i);                             // each one rewrites the sources of CENTER_EVT
        }

        traffic.get();
        await().atMost(Duration.ofSeconds(60)).until(() -> stored("rc1") == EVENTS && stored("rd1") == EVENTS);
    }
}
