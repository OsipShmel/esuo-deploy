package honey.road.node.it;

import honey.road.contracts.error.EsuoException;
import honey.road.node.demo.DemoResetService;
import honey.road.node.messaging.nats.NatsGateway;
import honey.road.node.planning.LoadPlanner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code POST /admin/demo/reset} and {@code GET /public/demo/status} (stage 6). */
@TestPropertySource(properties = "esuo.demo-mode=true")
class DemoResetIT extends AbstractNodeIT {
    @Autowired DemoResetService reset;
    @Autowired NatsGateway nats;
    @Autowired LoadPlanner load;

    private static final String PLANNING = """
            {"base_arrivals_per_day": 10, "utilization_target": 0.85,
             "weekdays": {"mon":"normal","tue":"normal","wed":"normal","thu":"normal","fri":"normal","sat":"normal","sun":"normal"},
             "profiles": {"normal": {"name":"Обычный день","flow_multiplier":1.0,
                          "opening_time":"00:00","closing_time":"23:59","hourly_curve":{"0":1},
                          "service_mix":{"parcel":1},"category_mix":{"default":1},"planned_windows":{"0":2}},
                          "pension": {"name":"Пенсионные выплаты","flow_multiplier":1.8,
                          "opening_time":"00:00","closing_time":"23:59","hourly_curve":{"0":1},
                          "service_mix":{"finance":1},"category_mix":{"pensioner":1},"planned_windows":{"0":3}}}}
            """;

    private void publishPlanning(String section) {
        jdbc.update("UPDATE branch_config SET planning = CAST(? AS jsonb), effective_version = effective_version + 1 "
                + "WHERE id=1", section);
    }

    @Test
    void resetClearsQueueAndClosesWindows() {
        openWindow(W1, E1, PARCEL, SEND);
        qr(PARCEL, "a");
        qr(PARCEL, "b");

        var result = reset.reset(null);

        assertThat(result.reset()).isTrue();
        assertThat(count("SELECT count(*) FROM tickets")).isZero();
        assertThat(count("SELECT count(*) FROM ticket_events")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM windows WHERE id=?", String.class, W1)).isEqualTo("closed");
    }

    @Test
    void resetClearsHandlingStatsAndLoadLevel() {
        jdbc.update("""
                INSERT INTO handling_stats(service_id, employee_id, client_category, avg_duration_sec, sample_count, updated_at)
                VALUES (?, ?, 'default', 120, 5, now())
                """, PARCEL, E1);
        jdbc.update("UPDATE load_state SET level='overload', level_since=now() WHERE id=1");

        reset.reset(null);

        assertThat(count("SELECT count(*) FROM handling_stats")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM load_state", Long.class)).isZero();
    }

    @Test
    void resetSelectsTheGivenScenarioProfile() {
        publishPlanning(PLANNING);

        var result = reset.reset("pension");

        assertThat(result.scenario()).isEqualTo("pension");
        assertThat(load.profileToday().profile().code()).isEqualTo("pension");
        assertThat(load.profileToday().source()).isEqualTo("manual");
    }

    @Test
    void resetRejectsAnUnknownScenario() {
        publishPlanning(PLANNING);

        assertThatThrownBy(() -> reset.reset("выплаты"))
                .isInstanceOf(EsuoException.class)
                .hasMessageContaining("normal");
    }

    @Test
    void statusReflectsTheAbsentNatsConnection() {
        // Тесты по замыслу поднимаются без NATS (AbstractNodeIT): связи с центром нет, и ручка это честно отражает.
        assertThat(nats.isConnected()).isFalse();
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
