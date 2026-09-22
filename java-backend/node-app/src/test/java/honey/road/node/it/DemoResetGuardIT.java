package honey.road.node.it;

import honey.road.contracts.error.EsuoException;
import honey.road.node.demo.DemoResetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code esuo.demo-mode} defaults to {@code false} in {@link AbstractNodeIT}: the reset button must not exist there. */
class DemoResetGuardIT extends AbstractNodeIT {
    @Autowired DemoResetService reset;

    @Test
    void resetIsRefusedOutsideDemoMode() {
        assertThatThrownBy(() -> reset.reset(null))
                .isInstanceOf(EsuoException.class)
                .hasMessageContaining("DEMO_MODE");
    }
}
