package honey.road.center.monitoring;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.stream.Stream;

/**
 * The typed columns of {@code branch_metrics} (V8, scale design 5.1): one place for the insert, the API, the rules and
 * their validation. A column is named like the heartbeat field it comes from; the migration declares the same names
 * and SQL types.
 */
public final class MetricColumns {
    public enum Type { INT, BIGINT, REAL, BOOL, TEXT }

    public record Column(String name, Type type) {
        /**
         * The JSON value as this column's Java type; {@code null} for anything else (a string where a number is
         * expected, a number out of range): one odd field must not fail the batch of the whole network.
         */
        public @Nullable Object convert(@Nullable JsonNode v) {
            if (v == null || v.isNull() || v.isMissingNode()) {
                return null;
            }
            return switch (type) {
                case TEXT -> v.isString() ? truncate(v.asString()) : null;
                case BOOL -> v.isBoolean() ? v.asBoolean() : null;
                case REAL -> number(v) instanceof Double d && Math.abs(d) <= Float.MAX_VALUE ? (Object) d.floatValue() : null;
                case INT -> number(v) instanceof Double d && Math.abs(d) <= Integer.MAX_VALUE ? (Object) (int) Math.round(d) : null;
                case BIGINT -> v.isIntegralNumber() && v.canConvertToLong() ? (Object) v.asLong()
                        : number(v) instanceof Double d && Math.abs(d) < 9.0e18 ? (Object) Math.round(d) : null;
            };
        }

        /** Booleans count as 1/0 in numeric columns, like the rules used to read them. */
        private static @Nullable Double number(JsonNode v) {
            if (v.isBoolean()) {
                return v.asBoolean() ? 1.0 : 0.0;
            }
            if (!v.isNumber()) {
                return null;
            }
            double d = v.asDouble();
            return Double.isFinite(d) ? d : null;
        }

        private static String truncate(String s) {
            return s.length() <= 64 ? s : s.substring(0, 64);
        }
    }

    /** The {@code sys} block of the node (design 6.1). */
    public static final List<Column> SYS = List.of(
            new Column("uptime_sec", Type.BIGINT), new Column("cpu_process", Type.REAL),
            new Column("cpu_system", Type.REAL), new Column("load_avg", Type.REAL), new Column("cpu_count", Type.INT),
            new Column("heap_used_mb", Type.INT), new Column("heap_max_mb", Type.INT), new Column("gc_pause_ms", Type.INT),
            new Column("db_pool_active", Type.INT), new Column("db_pool_pending", Type.INT),
            new Column("db_pool_max", Type.INT), new Column("db_size_mb", Type.INT), new Column("disk_free_mb", Type.INT),
            new Column("disk_total_mb", Type.INT), new Column("http_requests", Type.BIGINT),
            new Column("http_rps", Type.REAL), new Column("http_p95_ms", Type.REAL), new Column("http_5xx_rate", Type.REAL),
            new Column("sse_clients", Type.INT), new Column("leaf_connected", Type.BOOL),
            new Column("leaf_rtt_ms", Type.REAL), new Column("nats_reconnects", Type.INT),
            new Column("log_errors", Type.INT), new Column("log_warns", Type.INT));

    /** The queue part of the heartbeat. */
    public static final List<Column> QUEUE = List.of(
            new Column("waiting", Type.INT), new Column("overdue", Type.INT), new Column("oldest_wait_sec", Type.INT),
            new Column("windows_open", Type.INT), new Column("outbox_pending", Type.INT),
            new Column("outbox_lag_sec", Type.INT), new Column("load_level", Type.TEXT),
            new Column("clear_time_min", Type.REAL));

    /** Computed by the center: process start by the node's clock, and the clock skew. */
    public static final String STARTED_AT = "started_at";
    public static final String CLOCK_SKEW_MS = "clock_skew_ms";

    /** Every value column of a row, in insert order. */
    public static final List<String> ALL = Stream.concat(Stream.concat(SYS.stream(), QUEUE.stream()).map(Column::name),
            Stream.of(STARTED_AT, CLOCK_SKEW_MS)).toList();

    private MetricColumns() {
    }

    public static boolean isSys(String name) {
        return SYS.stream().anyMatch(c -> c.name().equals(name));
    }
}
