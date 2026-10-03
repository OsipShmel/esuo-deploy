package honey.road.node.monitoring;

import honey.road.contracts.event.Subjects;
import honey.road.node.it.AbstractNatsNodeIT;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** RPC {@code diag.logs} over a real request-reply, the way the center calls it (design 5.7). */
class DiagLogsRpcIT extends AbstractNatsNodeIT {
    static final Path LOG_FILE;

    static {
        try {
            LOG_FILE = Files.createTempDirectory("diag-logs").resolve("node.log");
            Files.writeString(LOG_FILE, String.join("\n",
                    "{\"@timestamp\":\"2026-10-01T10:00:00Z\",\"log\":{\"level\":\"INFO\"},\"message\":\"one\"}",
                    "{\"@timestamp\":\"2026-10-01T10:01:00Z\",\"log\":{\"level\":\"ERROR\"},\"message\":\"two\"}",
                    "{\"@timestamp\":\"2026-10-01T10:02:00Z\",\"log\":{\"level\":\"WARN\"},\"message\":\"three\"}") + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void logFile(DynamicPropertyRegistry registry) {
        registry.add("esuo.logs.file", LOG_FILE::toString);
    }

    static JsonNode call(String body) throws Exception {
        try (var c = testClient()) {
            await().atMost(Duration.ofSeconds(10)).until(() -> {
                try {
                    var r = c.request(Subjects.rpc("b01", "branch.info"), new byte[0], Duration.ofSeconds(2));
                    return r != null && !r.isStatusMessage();
                } catch (Exception e) {
                    return false;
                }
            });
            var reply = c.request(Subjects.rpc("b01", "diag.logs"), body.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(10));
            return JsonMapper.builder().build().readTree(reply.getData());
        }
    }

    @Test
    void logsOverRealRequestReply() throws Exception {
        var reply = call("{\"from\":\"2026-10-01T09:00:00Z\",\"to\":\"2026-10-01T11:00:00Z\",\"min_level\":\"debug\",\"limit\":2}");

        assertThat(reply.path("ok").asBoolean()).isTrue();
        var data = reply.path("data");
        assertThat(data.path("lines")).hasSize(2);
        assertThat(data.path("lines").get(0).asString()).contains("\"one\"");
        assertThat(data.path("next_cursor").asString()).startsWith("node.log:");
        assertThat(data.path("truncated").asBoolean()).isTrue();

        var rest = call("{\"from\":\"2026-10-01T09:00:00Z\",\"to\":\"2026-10-01T11:00:00Z\",\"limit\":2,\"cursor\":\""
                + data.path("next_cursor").asString() + "\"}");
        assertThat(rest.path("data").path("lines")).hasSize(1);
        assertThat(rest.path("data").path("next_cursor").isNull()).isTrue();
    }

    @Test
    void minLevelFilters() throws Exception {
        var reply = call("{\"from\":\"2026-10-01T09:00:00Z\",\"to\":\"2026-10-01T11:00:00Z\",\"min_level\":\"warn\"}");

        assertThat(reply.path("data").path("lines")).hasSize(2);
    }

    @Test
    void badRangeIsValidation() throws Exception {
        var reply = call("{\"from\":\"2026-10-01T11:00:00Z\",\"to\":\"2026-10-01T09:00:00Z\"}");

        assertThat(reply.path("ok").asBoolean()).isFalse();
        assertThat(reply.path("problem").path("code").asString()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void unknownLevelIsValidation() throws Exception {
        var reply = call("{\"min_level\":\"fatal\"}");

        assertThat(reply.path("problem").path("code").asString()).isEqualTo("VALIDATION_ERROR");
    }
}
