package honey.road.center.logs;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Loki push body (design 5.6): one stream per label set, values as [nanoseconds, line]. */
class LokiPushTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String LINE = "{\"message\":\"боль \\\"quoted\\\"\"}";

    private static JsonNode push(List<LogRecord> records) {
        return JSON.readTree(LokiPush.payload(records));
    }

    @Test
    void groupsByLabels() {
        var body = push(List.of(
                new LogRecord("b01", "error", "stream", null, 1L, "a"),
                new LogRecord("b02", "warn", "stream", null, 2L, "b"),
                new LogRecord("b01", "error", "stream", null, 3L, "c")));

        var streams = body.path("streams");
        assertThat(streams).hasSize(2);
        var first = streams.get(0);
        assertThat(first.path("stream").path("service").asString()).isEqualTo("node");
        assertThat(first.path("stream").path("branch").asString()).isEqualTo("b01");
        assertThat(first.path("stream").path("level").asString()).isEqualTo("error");
        assertThat(first.path("stream").path("source").asString()).isEqualTo("stream");
        assertThat(first.path("values")).hasSize(2);
    }

    @Test
    void pullHasPullLabel() {
        var body = push(List.of(new LogRecord("b01", "info", "pull", "p-1", 1L, "a"),
                new LogRecord("b01", "info", "stream", null, 1L, "b")));

        assertThat(body.path("streams").get(0).path("stream").path("pull").asString()).isEqualTo("p-1");
        assertThat(body.path("streams").get(1).path("stream").has("pull")).isFalse();
    }

    @Test
    void valuesAreNanosecondStringsAndRawLines() {
        var body = push(List.of(new LogRecord("b01", "error", "stream", null, 1790838000123456000L, LINE)));

        var value = body.path("streams").get(0).path("values").get(0);
        assertThat(value.get(0).asString()).isEqualTo("1790838000123456000");
        assertThat(value.get(1).asString()).isEqualTo(LINE);
    }
}
