package honey.road.node.monitoring;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shape of a node log line as Spring Boot's ECS encoder writes it — nested {@code log.level},
 * {@code event.kind}, {@code error.*}. The branch collector (infrastructure/vector/branch.yaml) parses exactly this.
 */
class EcsContractTest {

    private static JsonNode render(java.util.function.Consumer<Logger> write) {
        var context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        context.putObject(Environment.class.getName(), new MockEnvironment().withProperty("spring.application.name", "node-app"));
        var logger = context.getLogger("honey.road.node.Example");
        var captured = new ListAppender<ILoggingEvent>();
        captured.setContext(context);
        captured.start();
        logger.addAppender(captured);
        write.accept(logger);

        var encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat("ecs");
        encoder.start();
        var line = new String(encoder.encode(captured.list.getFirst()), StandardCharsets.UTF_8);
        assertThat(line).endsWith("\n").doesNotContain("\n\n");
        return JsonMapper.builder().build().readTree(line);
    }

    @Test
    void lifecycleRendersNested() {
        var json = render(log -> Lifecycle.info(log, "app.started", "node started branch={}", "b01"));

        assertThat(json.path("@timestamp").asString()).isNotBlank();
        assertThat(json.path("log").path("level").asString()).isEqualTo("INFO");
        assertThat(json.path("log").path("logger").asString()).isEqualTo("honey.road.node.Example");
        assertThat(json.path("message").asString()).isEqualTo("node started branch=b01");
        assertThat(json.path("event").path("kind").asString()).isEqualTo("lifecycle");
        assertThat(json.path("event").path("action").asString()).isEqualTo("app.started");
    }

    @Test
    void errorRendersStackTrace() {
        var json = render(log -> Lifecycle.error(log, "error.unhandled", new IllegalStateException("boom"), "request failed"));

        assertThat(json.path("log").path("level").asString()).isEqualTo("ERROR");
        assertThat(json.path("error").path("type").asString()).isEqualTo("java.lang.IllegalStateException");
        assertThat(json.path("error").path("message").asString()).isEqualTo("boom");
        assertThat(json.path("error").path("stack_trace").asString()).contains("IllegalStateException");
    }
}
