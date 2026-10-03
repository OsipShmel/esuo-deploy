package honey.road.center.monitoring;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * One row of {@code branch_metrics} (design 6.3): the {@code sys} block as sent, the queue part of the heartbeat and
 * the clock skew. The skew uses the branch's system time, not {@code sent_at}: under the demo clock the latter runs
 * hours ahead on purpose.
 */
public record MetricsRow(String branchId, Instant ts, @Nullable JsonNode sys, JsonNode queue, @Nullable Integer clockSkewMs) {
    static final List<String> QUEUE_FIELDS = List.of("waiting", "overdue", "oldest_wait_sec", "windows_open",
            "outbox_pending", "outbox_lag_sec", "load_level", "clear_time_min");

    public static MetricsRow of(String branchId, JsonNode hb, Instant receivedAt) {
        var queue = JsonNodeFactory.instance.objectNode();
        for (var field : QUEUE_FIELDS) {
            if (hb.has(field)) {
                queue.set(field, hb.get(field));
            }
        }
        var sys = hb.path("sys").isObject() ? hb.get("sys") : null;
        return new MetricsRow(branchId, receivedAt, sys, queue, sys == null ? null : skew(sys, receivedAt));
    }

    private static @Nullable Integer skew(JsonNode sys, Instant receivedAt) {
        try {
            long ms = Duration.between(Instant.parse(sys.path("sent_at_system").asString("")), receivedAt).toMillis();
            return (int) Math.clamp(ms, Integer.MIN_VALUE, Integer.MAX_VALUE);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
