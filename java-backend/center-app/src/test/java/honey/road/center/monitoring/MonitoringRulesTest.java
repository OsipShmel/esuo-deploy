package honey.road.center.monitoring;

import honey.road.center.monitoring.MonitoringRules.Alert;
import honey.road.center.monitoring.MonitoringRules.Sample;
import honey.road.center.monitoring.MonitoringRules.Series;
import honey.road.center.monitoring.MonitoringConfig.Rule;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Rules turn the metrics history into alerts with a human text (design 6.4). A pure function: no clock, no database. */
class MonitoringRulesTest {
    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Rule CPU = new Rule("sys.cpu", "threshold", "sys.cpu_system", null, ">", 0.9,
            Duration.ofMinutes(5), null, false, "warning", "ПК отделения не справляется: CPU {value:%}% уже {for} мин");
    private static final Rule DISK = new Rule("sys.disk", "ratio", "sys.disk_free_mb", "sys.disk_total_mb", "<", 0.1,
            null, null, false, "critical", "На диске отделения осталось {value:%}%");
    private static final Rule LINK = new Rule("sys.link", "increase", "sys.nats_reconnects", null, ">", 5.0,
            null, Duration.ofHours(1), false, "warning", "Нестабильная связь с центром: {value} переподключений за час");
    private static final Rule RESTART = new Rule("sys.restart", "decrease", "sys.uptime_sec", null, null, null,
            null, Duration.ofMinutes(30), false, "info", "Приложение отделения перезапускалось в {at}");
    private static final Rule CLOCK = new Rule("sys.clock", "threshold", "clock_skew_sec", null, ">", 60.0,
            Duration.ofMinutes(2), null, true, "warning", "Часы отделения расходятся с центром на {value} с");

    private static Sample at(long secondsAgo, String sys) {
        return new Sample(NOW.minusSeconds(secondsAgo), sys == null ? null : JSON.readTree(sys), null);
    }

    /** One point every 10 s from {@code fromAgo} down to {@code toAgo} seconds ago, inclusive. */
    private static List<Sample> every10s(long fromAgo, long toAgo, String sys) {
        var out = new ArrayList<Sample>();
        for (long s = fromAgo; s >= toAgo; s -= 10) {
            out.add(at(s, sys));
        }
        return out;
    }

    private static List<Alert> run(List<Sample> samples, Rule rule) {
        return MonitoringRules.evaluate(List.of(new Series("b01", MSK, samples)), List.of(rule), NOW, 1.0);
    }

    @Test
    void thresholdFiresWhenHeldForTheWholeDuration() {
        var alerts = run(every10s(360, 0, "{\"cpu_system\":0.95}"), CPU);

        assertThat(alerts).singleElement().satisfies(a -> {
            assertThat(a.branchId()).isEqualTo("b01");
            assertThat(a.ruleId()).isEqualTo("sys.cpu");
            assertThat(a.severity()).isEqualTo("warning");
            assertThat(a.text()).isEqualTo("ПК отделения не справляется: CPU 95% уже 6 мин");
            assertThat(a.since()).isEqualTo(NOW.minusSeconds(360));
        });
    }

    @Test
    void thresholdDoesNotFireWhenOnePointBreaksIt() {
        var samples = new ArrayList<>(every10s(360, 130, "{\"cpu_system\":0.95}"));
        samples.add(at(120, "{\"cpu_system\":0.5}"));
        samples.addAll(every10s(110, 0, "{\"cpu_system\":0.95}"));

        assertThat(run(samples, CPU)).isEmpty();
    }

    @Test
    void thresholdDoesNotFireOnStaleData() {
        assertThat(run(every10s(560, 180, "{\"cpu_system\":0.95}"), CPU)).isEmpty();
    }

    @Test
    void thresholdDoesNotFireBeforeDuration() {
        assertThat(run(every10s(240, 0, "{\"cpu_system\":0.95}"), CPU)).isEmpty();
    }

    @Test
    void thresholdDoesNotBridgeAGap() {
        var samples = new ArrayList<>(every10s(600, 400, "{\"cpu_system\":0.95}"));
        samples.addAll(every10s(100, 0, "{\"cpu_system\":0.95}"));              // 5 minutes without heartbeats in between

        assertThat(run(samples, CPU)).isEmpty();
    }

    @Test
    void ratioUsesTheLastPoint() {
        var samples = List.of(at(20, "{\"disk_free_mb\":30000,\"disk_total_mb\":60000}"),
                at(10, "{\"disk_free_mb\":5000,\"disk_total_mb\":60000}"));

        assertThat(run(samples, DISK)).singleElement().satisfies(a -> {
            assertThat(a.severity()).isEqualTo("critical");
            assertThat(a.text()).isEqualTo("На диске отделения осталось 8%");
        });
    }

    @Test
    void increaseSurvivesCounterReset() {
        var samples = List.of(at(50, "{\"nats_reconnects\":2}"), at(40, "{\"nats_reconnects\":4}"),
                at(30, "{\"nats_reconnects\":7}"), at(20, "{\"nats_reconnects\":0}"),       // node restarted
                at(10, "{\"nats_reconnects\":2}"));

        assertThat(run(samples, LINK)).singleElement()
                .satisfies(a -> assertThat(a.text()).isEqualTo("Нестабильная связь с центром: 7 переподключений за час"));
    }

    @Test
    void increaseBelowTheLimitIsQuiet() {
        assertThat(run(List.of(at(20, "{\"nats_reconnects\":1}"), at(10, "{\"nats_reconnects\":3}")), LINK)).isEmpty();
    }

    @Test
    void decreaseMeansRestart() {
        var samples = List.of(at(30, "{\"uptime_sec\":500}"), at(20, "{\"uptime_sec\":510}"), at(10, "{\"uptime_sec\":5}"));

        assertThat(run(samples, RESTART)).singleElement().satisfies(a -> {
            assertThat(a.since()).isEqualTo(NOW.minusSeconds(10));
            assertThat(a.text()).isEqualTo("Приложение отделения перезапускалось в 12:59");   // 09:59:50Z in Moscow
        });
    }

    @Test
    void absComparesTheModule() {
        var samples = new ArrayList<Sample>();
        for (long s = 150; s >= 0; s -= 10) {
            samples.add(new Sample(NOW.minusSeconds(s), JSON.readTree("{}"), -90_000));
        }

        assertThat(run(samples, CLOCK)).singleElement()
                .satisfies(a -> assertThat(a.text()).isEqualTo("Часы отделения расходятся с центром на 90 с"));
    }

    @Test
    void demoScaleShortensDurations() {
        var alerts = MonitoringRules.evaluate(List.of(new Series("b01", MSK, every10s(40, 0, "{\"cpu_system\":0.95}"))),
                List.of(CPU), NOW, 0.1);                                              // 5 min → 30 s

        assertThat(alerts).hasSize(1);
    }

    @Test
    void heldForIsAtLeastOneMinute() {                                     // demo scale fires after 30 s: "уже 0 мин" reads as a bug
        var alerts = MonitoringRules.evaluate(List.of(new Series("b01", MSK, every10s(40, 0, "{\"cpu_system\":0.95}"))),
                List.of(CPU), NOW, 0.1);

        assertThat(alerts).singleElement().satisfies(a -> assertThat(a.text()).endsWith("уже 1 мин"));
    }

    @Test
    void missingSysRaisesNothing() {
        var samples = every10s(400, 0, null);

        assertThat(MonitoringRules.evaluate(List.of(new Series("b01", MSK, samples)), List.of(CPU, DISK, LINK, RESTART),
                NOW, 1.0)).isEmpty();
    }

    @Test
    void eachBranchOnItsOwn() {
        var hot = new Series("b01", MSK, every10s(360, 0, "{\"cpu_system\":0.95}"));
        var calm = new Series("b02", MSK, every10s(360, 0, "{\"cpu_system\":0.2}"));

        assertThat(MonitoringRules.evaluate(List.of(hot, calm), List.of(CPU), NOW, 1.0))
                .extracting(Alert::branchId).containsExactly("b01");
    }
}
