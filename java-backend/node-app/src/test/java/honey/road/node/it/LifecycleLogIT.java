package honey.road.node.it;

import honey.road.node.messaging.inbox.BranchInboxProcessor;
import honey.road.node.queue.application.BranchConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Lifecycle events reach the log (console in tests, the same ECS format as the file the collector reads). */
@ExtendWith(OutputCaptureExtension.class)
class LifecycleLogIT extends AbstractNodeIT {
    @Autowired BranchInboxProcessor inbox;
    @Autowired BranchConfigService config;
    @Autowired TaskScheduler scheduler;

    @Test
    void configAppliedIsLifecycle(CapturedOutput output) {
        int version = config.view().centralVersion() + 1;

        inbox.process("center.config.branch.b01", "lc-" + version, "{\"version\":" + version
                + ",\"scope\":\"branch\",\"queue\":{\"slack_min\":7},\"bounds\":{\"slack_min\":{\"min\":0,\"max\":15}}}");

        assertThat(output.getOut()).contains("\"action\":\"config.applied\"").contains("version=" + version);
    }

    @Test
    void scheduledTaskFailureIsLifecycle(CapturedOutput output) {
        scheduler.schedule(() -> { throw new IllegalStateException("scheduled boom"); }, Instant.now());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getOut())
                .contains("\"action\":\"error.unhandled\"").contains("scheduled boom"));
    }
}
