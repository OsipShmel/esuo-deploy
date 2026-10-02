package honey.road.node.monitoring;

import honey.road.node.it.AbstractNatsNodeIT;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** No LOG_FILE (a local run): diag.logs says so with its own code instead of failing with 500. */
class DiagLogsUnavailableIT extends AbstractNatsNodeIT {

    @Test
    void logsUnavailableWithoutLogFile() throws Exception {
        var reply = DiagLogsRpcIT.call("{}");

        assertThat(reply.path("ok").asBoolean()).isFalse();
        assertThat(reply.path("status").asInt()).isEqualTo(409);
        assertThat(reply.path("problem").path("code").asString()).isEqualTo("LOGS_UNAVAILABLE");
    }
}
