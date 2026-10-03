package honey.road.center.monitoring;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code monitoring-rules.yml} (design 6.4). Checked while binding: a rule with an unknown kind, metric or operator,
 * or without what its kind needs, stops the start with the rule id in the message rather than never firing.
 */
@ConfigurationProperties("esuo.center.monitoring")
public record MonitoringConfig(Duration evaluateEvery, double demoDurationScale, List<Rule> rules) {
    static final Set<String> KINDS = Set.of("threshold", "ratio", "increase", "decrease");
    static final Set<String> OPERATORS = Set.of(">", ">=", "<", "<=");
    static final Set<String> SEVERITIES = Set.of("info", "warning", "critical");
    static final String CLOCK_SKEW = "clock_skew_sec";
    /** The numeric columns of the sys block (design 6.1, {@link MetricColumns}) and the clock skew the center computes. */
    static final Set<String> METRICS = Stream.concat(MetricColumns.SYS.stream()
                    .filter(c -> c.type() != MetricColumns.Type.TEXT).map(c -> "sys." + c.name()), Stream.of(CLOCK_SKEW))
            .collect(Collectors.toUnmodifiableSet());
    /** {@code decrease} is restart detection: it compares process starts, which only the uptime has. */
    static final String UPTIME = "sys.uptime_sec";

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
                if (value == null || !Double.isFinite(value)) {
                    throw invalid(id, "kind " + kind + " needs a numeric 'value'");
                }
            } else if (!UPTIME.equals(metric)) {
                throw invalid(id, "kind decrease detects restarts and works only on " + UPTIME + ", got '" + metric + "'");
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
