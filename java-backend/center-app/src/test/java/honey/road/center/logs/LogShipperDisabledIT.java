package honey.road.center.logs;

import honey.road.center.it.AbstractCenterNatsIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** No LOKI_URL: nothing is shipped and the lines wait in CENTER_LOG until a Loki appears. */
class LogShipperDisabledIT extends AbstractCenterNatsIT {
    @Autowired LogShipper shipper;

    @Test
    void emptyUrlDisablesShipper() {
        assertThat(shipper.isRunning()).isFalse();
    }
}
