package honey.road.center.monitoring;

import honey.road.center.monitoring.MonitoringConfig.Rule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The text of an alert (design 6.4). When a rule fires is checked against Postgres in {@code RuleQueryIT}; here only
 * what a person reads.
 */
class MonitoringRulesTest {
    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");

    private static Rule rule(String text) {
        return new Rule("sys.x", "threshold", "sys.cpu_system", null, ">", 0.9, Duration.ofMinutes(5), null, false,
                "warning", text);
    }

    private static String text(String template, double value, Instant since) {
        return MonitoringRules.alert("b01", rule(template), value, since, NOW, MSK).text();
    }

    @Test
    void percentRoundsTheFraction() {
        assertThat(text("CPU {value:%}%", 0.9499999880790710, NOW)).isEqualTo("CPU 95%");     // a real column read as float8
    }

    @Test
    void wholeNumbersHaveNoFraction() {
        assertThat(text("{value} переподключений", 7.0, NOW)).isEqualTo("7 переподключений");
        assertThat(text("p95 {value} мс", 1600.0000001, NOW)).isEqualTo("p95 1600 мс");
    }

    @Test
    void otherNumbersHaveOneDecimalWithAComma() {
        assertThat(text("RTT {value} мс", 12.34000015258789, NOW)).isEqualTo("RTT 12,3 мс");
    }

    @Test
    void forCountsWholeMinutesSinceTheStart() {
        assertThat(text("уже {for} мин", 0.95, NOW.minusSeconds(16 * 60 + 30))).isEqualTo("уже 16 мин");
    }

    @Test
    void forIsAtLeastOneMinute() {                                          // demo scale fires after 30 s
        assertThat(text("уже {for} мин", 0.95, NOW.minusSeconds(30))).isEqualTo("уже 1 мин");
    }

    @Test
    void atIsInTheBranchZone() {
        assertThat(text("перезапускалось в {at}", 5, Instant.parse("2026-10-02T09:59:45Z"))).isEqualTo("перезапускалось в 12:59");
    }

    @Test
    void alertCarriesTheRule() {
        var a = MonitoringRules.alert("b01", rule("t"), 0.95, NOW.minusSeconds(60), NOW, MSK);

        assertThat(a.branchId()).isEqualTo("b01");
        assertThat(a.ruleId()).isEqualTo("sys.x");
        assertThat(a.severity()).isEqualTo("warning");
        assertThat(a.since()).isEqualTo(NOW.minusSeconds(60));
    }
}
