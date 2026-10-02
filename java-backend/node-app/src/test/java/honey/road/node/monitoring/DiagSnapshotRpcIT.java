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
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** RPC {@code diag.snapshot} over a real request-reply, the way the center calls it (design 7). */
class DiagSnapshotRpcIT extends AbstractNatsNodeIT {
    static final Path LOG_FILE;

    static {
        try {
            LOG_FILE = Files.createTempDirectory("diag-snapshot").resolve("node.log");
            var now = Instant.now();
            Files.writeString(LOG_FILE, String.join("\n",
                    line(now.minusSeconds(120), "INFO", "lifecycle", "app.started", "node started"),
                    line(now.minusSeconds(90), "INFO", null, null, "a plain line"),
                    line(now.minusSeconds(60), "WARN", "lifecycle", "leaf.unlinked", "leaf is not linked")) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String line(Instant at, String level, String kind, String action, String message) {
        var event = kind == null ? "" : ",\"event\":{\"kind\":\"" + kind + "\",\"action\":\"" + action + "\"}";
        return "{\"@timestamp\":\"" + at + "\",\"log\":{\"level\":\"" + level + "\"},\"message\":\"" + message + "\"" + event + "}";
    }

    @DynamicPropertySource
    static void logFile(DynamicPropertyRegistry registry) {
        registry.add("esuo.logs.file", LOG_FILE::toString);
    }

    private static JsonNode call() throws Exception {
        try (var c = testClient()) {
            await().atMost(Duration.ofSeconds(10)).until(() -> {
                try {
                    var r = c.request(Subjects.rpc("b01", "branch.info"), new byte[0], Duration.ofSeconds(2));
                    return r != null && !r.isStatusMessage();
                } catch (Exception e) {
                    return false;
                }
            });
            var reply = c.request(Subjects.rpc("b01", "diag.snapshot"), new byte[0], Duration.ofSeconds(10));
            return JsonMapper.builder().build().readTree(reply.getData());
        }
    }

    @Test
    void snapshotOverRealRequestReply() throws Exception {
        var reply = call();

        assertThat(reply.path("ok").asBoolean()).isTrue();
        var data = reply.path("data");
        assertThat(data.path("nats").path("connected").asBoolean()).isTrue();
        assertThat(data.path("nats").path("streams").path("BRANCH_EVT").asBoolean()).isTrue();
        assertThat(data.path("db").has("error")).isFalse();
        var lifecycle = data.path("lifecycle").path("events");
        assertThat(lifecycle).hasSize(2);
        assertThat(lifecycle.get(0).path("action").asString()).isEqualTo("app.started");
        assertThat(lifecycle.get(1).path("action").asString()).isEqualTo("leaf.unlinked");
        assertThat(lifecycle.get(1).path("level").asString()).isEqualTo("WARN");
    }
}
