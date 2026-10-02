package honey.road.node.messaging.nats;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** RTT of the leaf-to-hub link from the leaf's {@code /leafz} (design 6.1). */
class LeafLinkMonitorTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void rttOfTheFirstLeaf() {
        var leafz = json.readTree("{\"leafs\":[{\"name\":\"hub\",\"rtt\":\"1.5ms\"},{\"rtt\":\"9ms\"}]}");

        assertThat(LeafLinkMonitor.rttOf(leafz)).isCloseTo(1.5, within(1e-9));
    }

    @Test
    void noLeafOrNoRttIsNull() {
        assertThat(LeafLinkMonitor.rttOf(json.readTree("{\"leafs\":[]}"))).isNull();
        assertThat(LeafLinkMonitor.rttOf(json.readTree("{\"leafs\":[{\"name\":\"hub\"}]}"))).isNull();
    }
}
