package honey.road.center.monitoring;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What fired becomes alerts: a continuing alert keeps its start, a restart is the moment of the latest one. */
class RuleEvaluatorTest {
    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");

    @Test
    void continuingThresholdKeepsItsStartAndForGrows() {
        var fired = new RuleQuery.Fired("b01", RuleQueryIT.CPU, 0.95, NOW.minusSeconds(420), MSK);

        var alerts = RuleEvaluator.alerts(List.of(fired), Map.of("b01/sys.cpu", NOW.minus(Duration.ofMinutes(16))), NOW);

        assertThat(alerts).singleElement().satisfies(a -> {
            assertThat(a.since()).isEqualTo(NOW.minus(Duration.ofMinutes(16)));
            assertThat(a.text()).endsWith("уже 16 мин");
        });
    }

    @Test
    void newAlertStartsWhenTheRuleSays() {
        var fired = new RuleQuery.Fired("b01", RuleQueryIT.CPU, 0.95, NOW.minusSeconds(360), MSK);

        assertThat(RuleEvaluator.alerts(List.of(fired), Map.of(), NOW)).singleElement()
                .satisfies(a -> assertThat(a.since()).isEqualTo(NOW.minusSeconds(360)));
    }

    @Test
    void anotherRestartMovesTheTime() {
        var fired = new RuleQuery.Fired("b01", RuleQueryIT.RESTART, 5, Instant.parse("2026-10-02T09:58:00Z"), MSK);

        var alerts = RuleEvaluator.alerts(List.of(fired), Map.of("b01/sys.restart", Instant.parse("2026-10-02T09:40:00Z")), NOW);

        assertThat(alerts).singleElement()
                .satisfies(a -> assertThat(a.text()).isEqualTo("Приложение отделения перезапускалось в 12:58"));
    }
}
