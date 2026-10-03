package honey.road.center.monitoring;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * One row of {@code branch_metrics} (design 6.3, scale design 5.1): typed values of the {@code sys} block and the queue
 * part of the heartbeat, the process start and the clock skew. The skew uses the branch's system time, not
 * {@code sent_at}: under the demo clock the latter runs hours ahead on purpose. The process start is taken by the
 * node's own clock, so that a heartbeat read late by a loaded center does not look like a restart.
 */
public record MetricsRow(String branchId, Instant ts, boolean hasSys, Map<String, Object> values) {

    public static MetricsRow of(String branchId, JsonNode hb, Instant receivedAt) {
        var values = new HashMap<String, Object>();
        for (var c : MetricColumns.QUEUE) {
            put(values, c.name(), c.convert(hb.get(c.name())));
        }
        var sys = hb.path("sys").isObject() ? hb.get("sys") : null;
        if (sys != null) {
            for (var c : MetricColumns.SYS) {
                put(values, c.name(), c.convert(sys.get(c.name())));
            }
            put(values, MetricColumns.CLOCK_SKEW_MS, skew(sys, receivedAt));
            put(values, MetricColumns.STARTED_AT, startedAt(sys));
        }
        return new MetricsRow(branchId, receivedAt, sys != null, Collections.unmodifiableMap(values));
    }

    public @Nullable Object value(String column) {
        return values.get(column);
    }

    private static void put(Map<String, Object> values, String column, @Nullable Object value) {
        if (value != null) {
            values.put(column, value);
        }
    }

    private static @Nullable Integer skew(JsonNode sys, Instant receivedAt) {
        var sent = instant(sys.get("sent_at_system"));
        if (sent == null) {
            return null;
        }
        long ms = Duration.between(sent, receivedAt).toMillis();
        return (int) Math.clamp(ms, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    private static @Nullable Instant startedAt(JsonNode sys) {
        var started = instant(sys.get("started_at"));
        if (started != null) {
            return started;
        }
        var sent = instant(sys.get("sent_at_system"));
        var uptime = sys.get("uptime_sec");
        return sent == null || uptime == null || !uptime.isNumber() ? null : sent.minusSeconds(uptime.asLong());
    }

    private static @Nullable Instant instant(@Nullable JsonNode v) {
        if (v == null || !v.isString()) {
            return null;
        }
        try {
            return Instant.parse(v.asString());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
