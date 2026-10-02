package honey.road.node.demo;

import honey.road.contracts.error.ErrorCode;
import honey.road.contracts.error.EsuoException;
import honey.road.node.it.AbstractNodeIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** In demo mode the endpoint writes an honest log line of the requested level — the scenarios and the demo use it. */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "esuo.demo-mode=true")
class DemoLogDemoModeIT extends AbstractNodeIT {
    @Autowired DemoLog demoLog;

    @Test
    void writesErrorWithDemoAction(CapturedOutput output) {
        demoLog.write("error", "demo boom 42");

        var line = output.getOut().lines().filter(l -> l.contains("demo boom 42")).findFirst().orElseThrow();
        assertThat(line).contains("\"level\":\"ERROR\"").contains("\"action\":\"demo.log\"")
                .contains("\"logger\":\"honey.road.node.demo.DemoLog\"");
    }

    @Test
    void rejectsUnknownLevel() {
        assertThatThrownBy(() -> demoLog.write("fatal", "x"))
                .isInstanceOfSatisfying(EsuoException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void rejectsBlankMessage() {
        assertThatThrownBy(() -> demoLog.write("warn", " "))
                .isInstanceOfSatisfying(EsuoException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
}
