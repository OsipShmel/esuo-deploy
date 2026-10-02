package honey.road.node.demo;

import honey.road.contracts.error.EsuoException;
import honey.road.node.it.AbstractNodeIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Outside demo mode (the {@link AbstractNodeIT} default) the demo log endpoint does not exist. */
class DemoLogIT extends AbstractNodeIT {
    @Autowired DemoLog demoLog;

    @Test
    void refusedOutsideDemoMode() {
        assertThatThrownBy(() -> demoLog.write("error", "demo boom"))
                .isInstanceOf(EsuoException.class).hasMessageContaining("DEMO_MODE");
    }
}
