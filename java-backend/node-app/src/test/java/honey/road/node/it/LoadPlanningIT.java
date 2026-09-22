package honey.road.node.it;

import honey.road.contracts.event.EventTypes;
import honey.road.node.api.admin.AdminLoadController;
import honey.road.node.planning.LoadLevel;
import honey.road.node.planning.LoadWatcher;
import honey.road.node.queue.application.Recommendations;
import honey.road.contracts.error.EsuoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Load planning end to end on a real database (load-planning.md 4, 6.1).
 *
 * <p>Two things are worth an integration test rather than a unit test. First, that a branch with no
 * {@code planning} section behaves exactly as it did before this block existed — publishing nothing is the off
 * switch, the same guarantee {@code DurationModelChainIT} makes for the duration model. Second, that the shape
 * of {@code GET /admin/load} matches what the admin screen was written against months before this endpoint
 * existed: the frontend is the contract here, not this code.
 *
 * <p>The hysteresis holds are set to zero in these tests on purpose — waiting two real minutes for a level to
 * rise would say nothing that {@code LoadLevelsTest} does not already check against a controlled clock.
 */
@SuppressWarnings("unchecked")
class LoadPlanningIT extends AbstractNodeIT {
    @Autowired AdminLoadController load;
    @Autowired LoadWatcher watcher;
    @Autowired Recommendations recommendations;

    /** Round the clock, so the test does not depend on the hour it runs at; two windows planned all day. */
    private static final String PLANNING = """
            {"base_arrivals_per_day": 100, "utilization_target": 0.85,
             "weekdays": {"mon":"normal","tue":"normal","wed":"normal","thu":"normal","fri":"normal",
                          "sat":"normal","sun":"normal"},
             "profiles": {"normal": {"name":"Обычный день","flow_multiplier":1.0,
                          "opening_time":"00:00","closing_time":"23:59",
                          "hourly_curve":{"0":1,"1":1,"2":1,"3":1,"4":1,"5":1,"6":1,"7":1,"8":1,"9":1,"10":1,"11":1,
                                          "12":1,"13":1,"14":1,"15":1,"16":1,"17":1,"18":1,"19":1,"20":1,"21":1,
                                          "22":1,"23":1},
                          "service_mix":{"parcel":1},"category_mix":{"default":1},"planned_windows":{"0":2}},
                          "pension": {"name":"Пенсионные выплаты","flow_multiplier":1.8,
                          "opening_time":"00:00","closing_time":"23:59","hourly_curve":{"0":1},
                          "service_mix":{"finance":1},"category_mix":{"pensioner":1},"planned_windows":{"0":3}}},
             "thresholds": {"up_hold_min": 0, "down_hold_min": 0}}""";

    private void publishPlanning(String section) {
        jdbc.update("UPDATE branch_config SET planning = CAST(? AS jsonb), effective_version = effective_version + 1 "
                + "WHERE id=1", section);
    }

    /**
     * Явно снимает секцию. Сбрасывать её перед каждым тестом умеет и {@code AbstractNodeIT}, но самый первый
     * тест прогона он пропускает — там он только снимает эталон конфигурации, — а узел при старте кладёт
     * в неё значения по умолчанию. Без этой строки проверки «планирования нет» зависели бы от порядка тестов.
     */
    private void clearPlanning() {
        publishPlanning("{}");
    }

    private static <T> T field(Map<String, Object> body, String name) {
        return (T) body.get(name);
    }

    // ------------------------------------------------------------------ выключено, пока не опубликовано

    @Test
    @DisplayName("без секции planning ручка отвечает, но профиля и прогноза нет")
    void withoutPlanningTheEndpointStillAnswers() {
        clearPlanning();
        openWindow(W1, E1, PARCEL, SEND);
        qr(PARCEL, "d1");

        var body = load.get();
        assertThat(body).doesNotContainKey("profile");
        assertThat((List<?>) field(body, "forecast")).isEmpty();
        assertThat(body.get("level")).isEqualTo(LoadLevel.NORMAL);
        assertThat((Map<String, Object>) field(body, "windows")).containsEntry("planned", 0);
    }

    @Test
    @DisplayName("без секции planning уровень не двигается вовсе")
    void withoutPlanningTheLevelNeverMoves() {
        clearPlanning();
        openWindow(W1, E1, PARCEL, SEND);
        for (int i = 0; i < 12; i++) {
            qr(PARCEL, "queue-" + i);
        }
        assertThat(watcher.apply()).as("нечего применять: порогов нет").isNull();
        assertThat(load.get().get("level")).isEqualTo(LoadLevel.NORMAL);
        assertThat(count("SELECT count(*) FROM outbox WHERE subject LIKE '%load.level_changed'")).isZero();
    }

    // ------------------------------------------------------------------ формат ответа

    @Test
    @DisplayName("GET /admin/load отдаёт ровно те поля, под которые написан экран")
    void responseShapeMatchesTheFrontend() {
        publishPlanning(PLANNING);
        openWindow(W1, E1, PARCEL, SEND);

        var body = load.get();
        assertThat(body).containsKeys("generated_at", "date", "profile", "level", "level_since",
                "clear_time_min", "ratio_now", "windows", "causes", "forecast");

        var profile = (Map<String, Object>) field(body, "profile");
        assertThat(profile).containsEntry("code", "normal").containsEntry("name", "Обычный день")
                .containsEntry("source", "weekday");

        var windows = (Map<String, Object>) field(body, "windows");
        assertThat(windows).containsEntry("open", 1).containsEntry("planned", 2);

        var forecast = (List<Map<String, Object>>) field(body, "forecast");
        assertThat(forecast).hasSize(24);
        assertThat(forecast.getFirst()).containsKeys("hour_start", "arrivals", "booked", "work_min",
                "capacity_min", "ratio", "level", "booking_quota");
        // 100 клиентов на 24 равных часа, по 300 с каждый: ≈ 20,8 мин работы против 102 мин мощности.
        assertThat((Double) forecast.getFirst().get("capacity_min")).isEqualTo(102.0);
        assertThat(forecast.getFirst().get("level")).isEqualTo(LoadLevel.NORMAL);

        var model = (Map<String, Object>) field(body, "duration_model");
        assertThat(model).as("без опубликованной модели прогноз стоит на каскаде средних")
                .containsEntry("basis", "cascade");
    }

    // ------------------------------------------------------------------ ручной профиль

    @Test
    @DisplayName("ручной профиль перекрывает календарь и виден в ответе сразу")
    void manualProfileWins() {
        publishPlanning(PLANNING);
        openWindow(W1, E1, PARCEL, SEND);

        var body = load.putProfile(new AdminLoadController.SetProfile("pension"));
        var profile = (Map<String, Object>) field(body, "profile");
        assertThat(profile).containsEntry("code", "pension").containsEntry("source", "manual");
        assertThat((Map<String, Object>) field(body, "windows")).containsEntry("planned", 3);

        var back = load.putProfile(new AdminLoadController.SetProfile(null));
        assertThat((Map<String, Object>) field(back, "profile")).containsEntry("source", "weekday");
    }

    @Test
    @DisplayName("неизвестный профиль отклоняется, а не молча игнорируется")
    void unknownProfileIsRejected() {
        publishPlanning(PLANNING);
        assertThatThrownBy(() -> load.putProfile(new AdminLoadController.SetProfile("выплаты")))
                .isInstanceOf(EsuoException.class)
                .hasMessageContaining("normal");
    }

    // ------------------------------------------------------------------ уровень и событие

    @Test
    @DisplayName("очередь, которую одно окно не разгребает, поднимает уровень и пишет событие")
    void aQueueTooLongForOneWindowRaisesTheLevel() {
        publishPlanning(PLANNING);
        openWindow(W1, E1, PARCEL, SEND);
        // По 300 с на талон (модели нет, статистики нет): 10 талонов на одно окно — 50 мин разгрузки
        // против порога перегрузки в 30 мин.
        for (int i = 0; i < 10; i++) {
            qr(PARCEL, "queue-" + i);
        }

        var state = watcher.apply();
        assertThat(state.level()).isEqualTo(LoadLevel.OVERLOAD);

        var body = load.get();
        assertThat(body.get("level")).isEqualTo(LoadLevel.OVERLOAD);
        assertThat((Double) body.get("clear_time_min")).isGreaterThan(30.0);

        var causes = (List<Map<String, Object>>) field(body, "causes");
        assertThat(causes).extracting(c -> c.get("code")).contains("few_windows");

        assertThat(count("SELECT count(*) FROM outbox WHERE subject LIKE ?",
                "%" + EventTypes.LOAD_LEVEL_CHANGED)).isEqualTo(1);
        var payload = jdbc.queryForObject("SELECT payload::text FROM outbox WHERE subject LIKE ?", String.class,
                "%" + EventTypes.LOAD_LEVEL_CHANGED);
        assertThat(payload).contains("\"from\":\"normal\"").contains("\"to\":\"overload\"")
                .contains("few_windows");
    }

    @Test
    @DisplayName("повторный проход при том же уровне события не плодит")
    void anUnchangedLevelIsNotRepublished() {
        publishPlanning(PLANNING);
        openWindow(W1, E1, PARCEL, SEND);
        for (int i = 0; i < 10; i++) {
            qr(PARCEL, "queue-" + i);
        }
        watcher.apply();
        watcher.apply();
        watcher.apply();

        assertThat(count("SELECT count(*) FROM outbox WHERE subject LIKE ?",
                "%" + EventTypes.LOAD_LEVEL_CHANGED)).isEqualTo(1);
    }

    @Test
    @DisplayName("спокойное отделение остаётся в normal и причин не называет")
    void calmBranchStaysNormal() {
        publishPlanning(PLANNING);
        openWindow(W1, E1, PARCEL, SEND);
        openWindow(W2, E2, PARCEL, FINANCE);
        qr(PARCEL, "d1");

        assertThat(watcher.apply().level()).isEqualTo(LoadLevel.NORMAL);
        var body = load.get();
        assertThat((List<?>) field(body, "causes")).isEmpty();
        assertThat((Double) body.get("clear_time_min")).isLessThan(15.0);
    }

    // ------------------------------------------------------------------ рекомендация по окнам

    @Test
    @DisplayName("нехватка окон по прогнозу попадает в рекомендацию как отдельная причина")
    void shortageReachesTheRecommendation() {
        publishPlanning(PLANNING);
        openWindow(W1, E1, PARCEL, SEND);
        for (int i = 0; i < 10; i++) {
            qr(PARCEL, "queue-" + i);
        }

        var result = recommendations.current();
        assertThat(result.recommendOpenWindow()).isTrue();
        assertThat(result.load()).isNotNull();
        assertThat(result.load().open()).isEqualTo(1);
        assertThat(result.load().shortOf()).as("по плану два окна, открыто одно").isGreaterThanOrEqualTo(1);
        assertThat(result.services()).isNotEmpty();
        assertThat(result.services().getFirst().reasons()).contains(Recommendations.REASON_LOAD_FORECAST);
    }

    @Test
    @DisplayName("без планирования рекомендация остаётся прежней: блок нагрузки просто отсутствует")
    void recommendationWithoutPlanning() {
        clearPlanning();
        openWindow(W1, E1, PARCEL, SEND);
        var result = recommendations.current();
        assertThat(result.load()).isNotNull();
        assertThat(result.load().shortOf()).as("профиля нет — плана нет — нехватки нет").isZero();
    }
}
