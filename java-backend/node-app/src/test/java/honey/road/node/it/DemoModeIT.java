package honey.road.node.it;

import honey.road.node.queue.application.OperatorQueries;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** With {@code esuo.demo-mode=true} the node runs on DemoClock, which is UTC only: no code may call {@code clock.withZone}. */
@TestPropertySource(properties = "esuo.demo-mode=true")
class DemoModeIT extends AbstractNodeIT {
    @Autowired OperatorQueries operatorQueries;

    @Test
    void operatorQueueWorksOnDemoClock() {
        var id = qr(PARCEL, "a");

        var queue = operatorQueries.queue(null, null);

        assertThat(queue).extracting(OperatorQueries.QueueItem::ticketId).contains(id);
    }
}
