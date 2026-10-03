package honey.road.center.monitoring;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * One row of {@code branch_metrics} (design 6.3, scale design 5.1): typed values of the {@code sys} block and the queue
 * part of the heartbeat, the process start and the clock skew, in the order of {@link MetricColumns#ALL}. The skew uses
 * the branch's system time, not {@code sent_at}: under the demo clock the latter runs hours ahead on purpose. The
 * process start is taken by the node's own clock, so that a heartbeat read late by a loaded center does not look like
 * a restart.
 */
public record MetricsRow(String branchId, Instant ts, boolean hasSys, @Nullable Object[] values) {
    /** Position of each column of {@link MetricColumns#ALL} in {@link #values}: a row is an array, not a map. */
    private static final Map<String, Integer> INDEX = index();

    public static MetricsRow of(String branchId, JsonNode hb, Instant receivedAt) {
        var values = new Object[MetricColumns.ALL.size()];
        for (var c : MetricColumns.QUEUE) {
            values[INDEX.get(c.name())] = c.convert(hb.get(c.name()));
        }
        var sys = hb.path("sys").isObject() ? hb.get("sys") : null;
        if (sys != null) {
            for (var c : MetricColumns.SYS) {
                values[INDEX.get(c.name())] = c.convert(sys.get(c.name()));
            }
            values[INDEX.get(MetricColumns.CLOCK_SKEW_MS)] = skew(sys, receivedAt);
            values[INDEX.get(MetricColumns.STARTED_AT)] = startedAt(sys);
        }
        return new MetricsRow(branchId, receivedAt, sys != null, values);
    }

    public @Nullable Object value(String column) {
        return values[INDEX.get(column)];
    }

    private static Map<String, Integer> index() {
        var m = new HashMap<String, Integer>();
        for (int i = 0; i < MetricColumns.ALL.size(); i++) {
            m.put(MetricColumns.ALL.get(i), i);
        }
        return Map.copyOf(m);
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
