package honey.road.center.monitoring;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code monitoring-rules.yml} (design 6.4). Checked while binding: a rule with an unknown kind, metric or operator,
 * or without what its kind needs, stops the start with the rule id in the message rather than never firing.
 */
@ConfigurationProperties("esuo.center.monitoring")
public record MonitoringConfig(Duration evaluateEvery, double demoDurationScale, List<Rule> rules) {
    static final Set<String> KINDS = Set.of("threshold", "ratio", "increase", "decrease");
    static final Set<String> OPERATORS = Set.of(">", ">=", "<", "<=");
    static final Set<String> SEVERITIES = Set.of("info", "warning", "critical");
    /** The sys block of the node (design 6.1) and the clock skew the center computes. */
    static final Set<String> METRICS = Set.of("sys.uptime_sec", "sys.cpu_process", "sys.cpu_system", "sys.load_avg",
            "sys.cpu_count", "sys.heap_used_mb", "sys.heap_max_mb", "sys.gc_pause_ms", "sys.db_pool_active",
            "sys.db_pool_pending", "sys.db_pool_max", "sys.db_size_mb", "sys.disk_free_mb", "sys.disk_total_mb",
            "sys.http_requests", "sys.http_rps", "sys.http_p95_ms", "sys.http_5xx_rate", "sys.sse_clients",
            "sys.leaf_connected", "sys.leaf_rtt_ms", "sys.nats_reconnects", "sys.log_errors", "sys.log_warns",
            "clock_skew_sec");

    public MonitoringConfig {
        evaluateEvery = evaluateEvery == null ? Duration.ofSeconds(15) : evaluateEvery;
        demoDurationScale = demoDurationScale <= 0 ? 1.0 : demoDurationScale;
        rules = rules == null ? List.of() : List.copyOf(rules);
        var ids = new HashSet<String>();
        for (var rule : rules) {
            if (!ids.add(rule.id())) {
                throw new IllegalArgumentException("monitoring rule " + rule.id() + ": duplicate id");
            }
        }
    }

    /**
     * @param of        the denominator of a {@code ratio}
     * @param holdFor   how long a {@code threshold} must hold ({@code for} in the file)
     * @param window    the history an {@code increase} or {@code decrease} looks at
     * @param abs       compare the absolute value (clock skew goes both ways)
     */
    public record Rule(String id, String kind, String metric, @Nullable String of, @Nullable String op,
                       @Nullable Double value, @Name("for") @Nullable Duration holdFor, @Nullable Duration window,
                       boolean abs, String severity, String text) {
        public Rule {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("monitoring rule without id");
            }
            if (!in(KINDS, kind)) {
                throw invalid(id, "unknown kind '" + kind + "', expected one of " + KINDS);
            }
            if (!in(METRICS, metric)) {
                throw invalid(id, "unknown metric '" + metric + "'");
            }
            if (!in(SEVERITIES, severity)) {
                throw invalid(id, "unknown severity '" + severity + "', expected one of " + SEVERITIES);
            }
            if (text == null || text.isBlank()) {
                throw invalid(id, "text is empty");
            }
            if (!"decrease".equals(kind)) {
                if (!in(OPERATORS, op)) {
                    throw invalid(id, "unknown operator '" + op + "', expected one of " + OPERATORS);
                }
                if (value == null) {
                    throw invalid(id, "kind " + kind + " needs 'value'");
                }
            }
            switch (kind) {
                case "threshold" -> require(id, kind, "for", holdFor);
                case "ratio" -> {
                    if (!in(METRICS, of)) {
                        throw invalid(id, "kind ratio needs 'of' with a known metric, got '" + of + "'");
                    }
                }
                default -> require(id, kind, "window", window);
            }
        }

        /** The stretch of history the rule looks back at. */
        Duration span() {
            return holdFor != null ? holdFor : window != null ? window : Duration.ZERO;
        }

        private static void require(String id, String kind, String field, @Nullable Duration value) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw invalid(id, "kind " + kind + " needs '" + field + "'");
            }
        }

        private static boolean in(Set<String> allowed, @Nullable String value) {
            return value != null && allowed.contains(value);             // Set.of(...).contains(null) throws
        }

        private static IllegalArgumentException invalid(String id, String problem) {
            return new IllegalArgumentException("monitoring rule " + id + ": " + problem);
        }
    }
}
