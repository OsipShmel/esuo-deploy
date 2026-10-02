package honey.road.center.monitoring;

import honey.road.center.monitoring.MonitoringConfig.Rule;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoublePredicate;

/**
 * Rules over the metrics history (design 6.4): series of points per branch → alerts with a text for a person.
 * A pure function: the evaluator reads the history and stores the result.
 *
 * <p>Every rule needs a fresh last point (≤ 2 min): a branch without heartbeats is {@code offline}, not "still hot".
 * Points without the metric are skipped; a gap longer than that between two points breaks a {@code threshold} run.
 */
public final class MonitoringRules {
    static final Duration FRESH = Duration.ofMinutes(2);
    private static final Locale RU = Locale.forLanguageTag("ru");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** One row of {@code branch_metrics} as the rules need it. */
    public record Sample(Instant ts, @Nullable JsonNode sys, @Nullable Integer clockSkewMs) {
    }

    /** Points of one branch, oldest first; the zone formats {@code {at}}. */
    public record Series(String branchId, ZoneId zone, List<Sample> samples) {
    }

    public record Alert(String branchId, String ruleId, String severity, String text, double value, Instant since) {
    }

    private record Point(Instant ts, double value) {
    }

    private MonitoringRules() {
    }

    /** @param durationScale multiplies {@code for} and {@code window}: 0.1 in demo mode, 1 otherwise */
    public static List<Alert> evaluate(List<Series> series, List<Rule> rules, Instant now, double durationScale) {
        var alerts = new ArrayList<Alert>();
        for (var s : series) {
            for (var rule : rules) {
                var alert = evaluate(s, rule, now, durationScale);
                if (alert != null) {
                    alerts.add(alert);
                }
            }
        }
        return alerts;
    }

    static Duration scaled(Duration d, double scale) {
        return Duration.ofMillis(Math.round(d.toMillis() * scale));
    }

    private static @Nullable Alert evaluate(Series s, Rule rule, Instant now, double scale) {
        var points = "ratio".equals(rule.kind()) ? ratios(s.samples(), rule) : points(s.samples(), rule.metric(), rule.abs());
        if (points.isEmpty() || points.getLast().ts().isBefore(now.minus(FRESH))) {
            return null;
        }
        return switch (rule.kind()) {
            case "threshold" -> threshold(s, rule, points, now, scaled(rule.holdFor(), scale));
            case "ratio" -> {
                var since = runStart(points, holds(rule));
                yield since == null ? null : alert(s, rule, points.getLast().value(), since, now);
            }
            case "increase" -> increase(s, rule, within(points, now, scaled(rule.window(), scale)), now);
            default -> decrease(s, rule, within(points, now, scaled(rule.window(), scale)), now);
        };
    }

    private static @Nullable Alert threshold(Series s, Rule rule, List<Point> points, Instant now, Duration hold) {
        var since = runStart(points, holds(rule));
        if (since == null || Duration.between(since, now).compareTo(hold) < 0) {
            return null;
        }
        return alert(s, rule, points.getLast().value(), since, now);
    }

    /** Growth of a counter; a drop means the node restarted and the counter began again from zero. */
    private static @Nullable Alert increase(Series s, Rule rule, List<Point> points, Instant now) {
        if (points.size() < 2) {
            return null;
        }
        double total = 0;
        Instant since = null;
        for (int i = 1; i < points.size(); i++) {
            double prev = points.get(i - 1).value();
            double cur = points.get(i).value();
            double delta = cur >= prev ? cur - prev : cur;
            if (delta > 0 && since == null) {
                since = points.get(i).ts();
            }
            total += delta;
        }
        return since != null && holds(rule).test(total) ? alert(s, rule, total, since, now) : null;
    }

    /** The latest drop within the window: for {@code uptime_sec}, the moment of a restart. */
    private static @Nullable Alert decrease(Series s, Rule rule, List<Point> points, Instant now) {
        for (int i = points.size() - 1; i > 0; i--) {
            if (points.get(i).value() < points.get(i - 1).value()) {
                return alert(s, rule, points.get(i).value(), points.get(i).ts(), now);
            }
        }
        return null;
    }

    /** Start of the run of points meeting the condition that reaches the last point; {@code null} if the last one fails. */
    private static @Nullable Instant runStart(List<Point> points, DoublePredicate holds) {
        Instant since = null;
        Instant previous = null;
        for (var p : points) {
            boolean gap = previous != null && Duration.between(previous, p.ts()).compareTo(FRESH) > 0;
            if (!holds.test(p.value())) {
                since = null;
            } else if (since == null || gap) {
                since = p.ts();
            }
            previous = p.ts();
        }
        return since;
    }

    private static DoublePredicate holds(Rule rule) {
        double limit = rule.value() == null ? 0 : rule.value();
        return switch (rule.op() == null ? "" : rule.op()) {
            case ">" -> v -> v > limit;
            case ">=" -> v -> v >= limit;
            case "<" -> v -> v < limit;
            case "<=" -> v -> v <= limit;
            default -> v -> false;
        };
    }

    private static List<Point> within(List<Point> points, Instant now, Duration window) {
        var from = now.minus(window);
        return points.stream().filter(p -> !p.ts().isBefore(from)).toList();
    }

    private static List<Point> points(List<Sample> samples, String metric, boolean abs) {
        var out = new ArrayList<Point>(samples.size());
        for (var s : samples) {
            var v = value(s, metric);
            if (v != null) {
                out.add(new Point(s.ts(), abs ? Math.abs(v) : v));
            }
        }
        return out;
    }

    private static List<Point> ratios(List<Sample> samples, Rule rule) {
        var out = new ArrayList<Point>(samples.size());
        for (var s : samples) {
            var part = value(s, rule.metric());
            var whole = value(s, rule.of());
            if (part != null && whole != null && whole > 0) {
                out.add(new Point(s.ts(), part / whole));
            }
        }
        return out;
    }

    private static @Nullable Double value(Sample s, @Nullable String metric) {
        if ("clock_skew_sec".equals(metric)) {
            return s.clockSkewMs() == null ? null : s.clockSkewMs() / 1000.0;
        }
        if (metric == null || s.sys() == null || !metric.startsWith("sys.")) {
            return null;
        }
        var node = s.sys().path(metric.substring(4));
        if (node.isNumber()) {
            return node.asDouble();
        }
        return node.isBoolean() ? (node.asBoolean() ? 1.0 : 0.0) : null;
    }

    private static Alert alert(Series s, Rule rule, double value, Instant since, Instant now) {
        var text = rule.text()
                .replace("{value:%}", String.valueOf(Math.round(value * 100)))
                .replace("{value}", number(value))
                .replace("{for}", String.valueOf(Duration.between(since, now).toMinutes()))
                .replace("{at}", HH_MM.format(since.atZone(s.zone())));
        return new Alert(s.branchId(), rule.id(), rule.severity(), text, value, since);
    }

    private static String number(double v) {
        return Math.abs(v - Math.rint(v)) < 1e-9 ? String.valueOf((long) Math.rint(v)) : String.format(RU, "%.1f", v);
    }
}
