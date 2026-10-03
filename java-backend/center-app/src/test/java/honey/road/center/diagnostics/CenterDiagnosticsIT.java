package honey.road.center.diagnostics;

import honey.road.center.it.AbstractCenterIT;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A snapshot of the center itself (scale design 6.2): which threads burn the CPU right now — garbage collector or
 * code — and the state of its memory, queues and tasks, in one request.
 */
class CenterDiagnosticsIT extends AbstractCenterIT {

    private JsonNode snapshot() {
        var result = mvc.post().uri("/api/v1/center/diagnostics").header("Authorization", "Bearer " + adminToken()).exchange();
        result.assertThat().hasStatusOk();
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    @Test
    void requiresTheNetworkAdmin() {
        mvc.post().uri("/api/v1/center/diagnostics").exchange().assertThat().hasStatus(401);
    }

    @Test
    void hotThreadsWithTheirStacks() {
        var threads = snapshot().path("threads");

        assertThat(threads.path("sample_ms").asInt()).isGreaterThanOrEqualTo(500);
        assertThat(threads.path("java")).isNotEmpty();
        var top = threads.path("java").get(0);
        assertThat(top.path("name").asString()).isNotBlank();
        assertThat(top.has("cpu_ms")).isTrue();
        assertThat(top.path("state").asString()).isNotBlank();
        assertThat(top.path("stack").isArray()).isTrue();
        // native threads (GC, JIT) come from /proc: present on Linux, explained elsewhere
        assertThat(threads.path("native").isArray() || threads.path("native").isString()).isTrue();
    }

    @Test
    void memoryGcAndCpu() {
        var s = snapshot();

        assertThat(s.path("heap").path("used_mb").asLong()).isPositive();
        assertThat(s.path("heap").path("max_mb").asLong()).isPositive();
        assertThat(s.path("gc")).isNotEmpty();
        assertThat(s.path("gc").get(0).has("collections")).isTrue();
        assertThat(s.path("cpu").has("process")).isTrue();
        assertThat(s.path("cpu").path("cores").asInt()).isPositive();
    }

    @Test
    void queuesAndTasksOfTheCenter() {
        var s = snapshot();

        assertThat(s.path("queues").has("heartbeats_buffered")).isTrue();
        assertThat(s.path("queues").has("onboarding")).isTrue();
        assertThat(s.path("queues").has("events_pending")).isTrue();
        assertThat(s.path("db_pool").has("active")).isTrue();
        assertThat(s.path("nats").has("state")).isTrue();
        assertThat(s.path("tasks").isArray()).isTrue();
    }
}
