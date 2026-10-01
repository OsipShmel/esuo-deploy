package honey.road.node.demo;

import honey.road.contracts.error.EsuoException;
import honey.road.node.it.AbstractNodeIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Outside demo mode (the {@link AbstractNodeIT} default) the scenario console does not exist: no call leaves the node. */
class DemoRunnerGuardIT extends AbstractNodeIT {
    @Autowired DemoRunnerController proxy;

    @Test
    void consoleIsOffOutsideDemoMode() {
        assertThatThrownBy(() -> proxy.scenarios()).isInstanceOf(EsuoException.class).hasMessageContaining("DEMO_MODE");
        assertThatThrownBy(() -> proxy.start("{\"code\":\"doctor\"}")).isInstanceOf(EsuoException.class);
        assertThatThrownBy(() -> proxy.stop()).isInstanceOf(EsuoException.class);
    }
}
