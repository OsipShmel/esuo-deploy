package honey.road.center.monitoring;

import honey.road.center.monitoring.MonitoringConfig.Rule;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Alerts of the monitoring rules (design 6.4) and their text for a person. The rules themselves run in SQL
 * ({@link RuleQuery}); this is the part that is the same for every kind.
 */
public final class MonitoringRules {
    private static final Locale RU = Locale.forLanguageTag("ru");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    public record Alert(String branchId, String ruleId, String severity, String text, double value, Instant since) {
    }

    private MonitoringRules() {
    }

    /** @param durationScale multiplies {@code for} and {@code window}: 0.1 in demo mode, 1 otherwise */
    static Duration scaled(Duration d, double durationScale) {
        return Duration.ofMillis(Math.round(d.toMillis() * durationScale));
    }

    /**
     * The alert with its text: {@code {value}}, {@code {value:%}} (×100), {@code {for}} (whole minutes since
     * {@code since}, at least 1 — under the demo scale a rule fires after 30 s and "0 мин" reads as a bug),
     * {@code {at}} ({@code since} as HH:mm in the branch's zone).
     */
    public static Alert alert(String branchId, Rule rule, double value, Instant since, Instant now, ZoneId zone) {
        var text = rule.text()
                .replace("{value:%}", String.valueOf(Math.round(value * 100)))
                .replace("{value}", number(value))
                .replace("{for}", String.valueOf(Math.max(1, Duration.between(since, now).toMinutes())))
                .replace("{at}", HH_MM.format(since.atZone(zone)));
        return new Alert(branchId, rule.id(), rule.severity(), text, value, since);
    }

    private static String number(double v) {
        return Math.abs(v - Math.rint(v)) < 1e-6 ? String.valueOf((long) Math.rint(v)) : String.format(RU, "%.1f", v);
    }
}
