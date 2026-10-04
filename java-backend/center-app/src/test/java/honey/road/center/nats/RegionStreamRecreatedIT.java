package honey.road.center.nats;

import honey.road.contracts.event.Subjects;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.awaitility.Awaitility.await;

/**
 * A regional leaf whose storage was lost (a reinstalled machine, a recreated container) creates its {@code REGION_EVT}
 * anew, numbered from 1 again. The hub must read the new stream from its start: NATS before 2.15 kept reading from the
 * old position and silently skipped as many events as the old stream had held (nats-server#6206). The same holds for
 * a branch leaf and its {@code BRANCH_EVT}.
 */
class RegionStreamRecreatedIT extends AbstractRegionsIT {

    @Test
    void eventsOfARegionStreamCreatedAnewReachTheCenter() throws Exception {
        try (var region = connect(REGION_1)) {
            createRegionStream(region);
            await().atMost(Duration.ofSeconds(10)).until(() -> streams.attachRegion("region-1"));
            awaitStored(publish(region, "rr1"));
            awaitStored(publish(region, "rr1"));

            region.jetStreamManagement().deleteStream(Subjects.STREAM_REGION_EVT);
            createRegionStream(region);

            awaitStored(publish(region, "rr1"));                       // number 1 of the new stream: 2.10 never delivered it
        }
    }
}
