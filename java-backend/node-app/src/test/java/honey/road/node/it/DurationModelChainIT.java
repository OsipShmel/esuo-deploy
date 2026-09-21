package honey.road.node.it;

import honey.road.node.model.ChainedDurationModel;
import honey.road.node.model.DurationModel;
import honey.road.node.queue.scoring.HeuristicScoringStrategy;
import honey.road.node.persistence.TicketRepository;
import honey.road.node.persistence.WindowRepository;
import honey.road.node.queue.scoring.ScoreResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fallback chain (ml-model.md 5.3) and, above all, the guarantee that turning the model off is free.
 *
 * <p>A branch with no {@code model} section — which is every branch until someone publishes one — must behave
 * exactly as it did before the model existed. That property is what makes the feature safe to ship mid-hackathon:
 * publishing nothing is the off switch, and {@code enabled: false} returns to it without a restart.
 */
class DurationModelChainIT extends AbstractNodeIT {
    @Autowired ChainedDurationModel durations;
    @Autowired HeuristicScoringStrategy heuristic;
    @Autowired TicketRepository ticketRepo;
    @Autowired WindowRepository windowRepo;

    /** A model that would give a very different answer, so any accidental use is obvious. */
    private static final String MODEL = """
            {"version":"dm-it","enabled":true,"status":"active","use_window":false,
             "mu":6.5,"a":{"parcel":0.0},"sigma":{"parcel":0.35},"sigma_default":0.35,
             "counts":{"a":{"parcel":500}}}""";

    private void publishModel(String section) {
        jdbc.update("UPDATE branch_config SET model = CAST(? AS jsonb), effective_version = effective_version + 1 "
                + "WHERE id=1", section);
    }

    private void stats(UUID service, UUID employee, String category, double avgSec, int samples) {
        jdbc.update("""
                INSERT INTO handling_stats(service_id, employee_id, window_id, client_category, avg_duration_sec, sample_count, updated_at)
                VALUES (?, ?, NULL, ?, ?, ?, now())
                """, service, employee, category, avgSec, samples);
    }

    private ScoreResult score(UUID ticketId, UUID employee) {
        var ticket = ticketRepo.byId("b01", ticketId, false);
        return heuristic.score(ticket, windowRepo.get("b01", W1), employee);
    }

    // ------------------------------------------------------------------ выключено по умолчанию

    @Test
    @DisplayName("без секции model прогноз идёт по каскаду, как до появления модели")
    void withoutAModelTheCascadeAnswers() {
        stats(PARCEL, E1, "default", 123, 10);

        var prediction = durations.predict(PARCEL, "default", E1, 11, W1);

        assertThat(prediction.basis()).isEqualTo("employee_service_category");
        assertThat(prediction.p50().toSeconds()).isEqualTo(123);
        assertThat(prediction.mean()).isEqualTo(prediction.p50());      // у среднего нет разброса
        assertThat(prediction.modelVersion()).isEqualTo("handling_stats-v1");
    }

    @Test
    @DisplayName("без статистики и без модели — значение по умолчанию из конфигурации")
    void withoutAnythingTheConfiguredDefaultAnswers() {
        var prediction = durations.predict(PARCEL, "default", E1, 11, W1);

        assertThat(prediction.basis()).isEqualTo("fallback");
        assertThat(prediction.p50().toSeconds()).isEqualTo(300);
        assertThat(prediction.confidence()).isEqualTo("low");
    }

    @Test
    @DisplayName("скоринг без модели даёт ровно прежние числа")
    void scoringWithoutAModelIsUnchanged() {
        stats(PARCEL, E1, "default", 200, 10);
        var ticket = qr(PARCEL, "no-model");

        var result = score(ticket, E1);

        assertThat(result.strategy()).isEqualTo("heuristic");
        assertThat(result.modelVersion()).isEqualTo("handling_stats-v1");
        assertThat(result.basis()).isEqualTo("employee_service_category");
        assertThat(result.predictedDuration().toSeconds()).isEqualTo(200);
        assertThat(result.value()).isEqualTo(1.0 / 200);
    }

    // ------------------------------------------------------------------ включение и выключение

    @Test
    @DisplayName("опубликованная модель перебивает каскад")
    void publishedModelTakesOverFromTheCascade() {
        stats(PARCEL, E1, "default", 123, 10);
        publishModel(MODEL);

        var prediction = durations.predict(PARCEL, "default", E1, 11, W1);

        assertThat(prediction.basis()).isEqualTo(DurationModel.Prediction.BASIS_MODEL_PARTIAL);
        assertThat(prediction.modelVersion()).isEqualTo("dm-it");
        assertThat(prediction.p50().toSeconds()).isEqualTo(665);        // exp(6.5), а не 123 из каскада
        assertThat(prediction.mean()).isGreaterThan(prediction.p50());  // у модели есть разброс
        assertThat(prediction.p90()).isGreaterThan(prediction.mean());
    }

    @Test
    @DisplayName("enabled: false мгновенно возвращает на каскад, коэффициенты не теряются")
    void disablingReturnsToTheCascadeWithoutLosingCoefficients() {
        stats(PARCEL, E1, "default", 123, 10);
        publishModel(MODEL);
        assertThat(durations.predict(PARCEL, "default", E1, 11, W1).modelVersion()).isEqualTo("dm-it");

        publishModel(MODEL.replace("\"enabled\":true", "\"enabled\":false"));

        var prediction = durations.predict(PARCEL, "default", E1, 11, W1);
        assertThat(prediction.basis()).isEqualTo("employee_service_category");
        assertThat(prediction.p50().toSeconds()).isEqualTo(123);
        // Коэффициенты остались в конфигурации: включить обратно — это одно значение, а не повторная публикация.
        assertThat(durations.params().version()).isEqualTo("dm-it");
        assertThat(durations.params().enabled()).isFalse();
    }

    @Test
    @DisplayName("разное содержимое под одним номером версии не путается в кеше")
    void twoSectionsUnderTheSameVersionAreNotConfused() {
        stats(PARCEL, E1, "default", 123, 10);

        // Номер версии конфигурации специально не меняется: так бывает после восстановления базы
        // или сброса стенда, когда номер уезжает назад и начинает повторяться. Кеш модели обязан
        // смотреть на содержимое секции, а не на номер, иначе он молча отдаёт чужую модель.
        jdbc.update("UPDATE branch_config SET model = CAST(? AS jsonb) WHERE id=1", MODEL);
        assertThat(durations.predict(PARCEL, "default", E1, 11, W1).modelVersion()).isEqualTo("dm-it");

        jdbc.update("UPDATE branch_config SET model = '{}' WHERE id=1");
        assertThat(durations.predict(PARCEL, "default", E1, 11, W1).basis())
                .isEqualTo("employee_service_category");
    }

    @Test
    @DisplayName("испорченная секция не роняет зал, а уводит на каскад")
    void brokenSectionFallsBackInsteadOfFailing() {
        stats(PARCEL, E1, "default", 123, 10);
        publishModel("{\"version\":\"dm-broken\",\"mu\":\"не число\"}");

        var prediction = durations.predict(PARCEL, "default", E1, 11, W1);

        assertThat(prediction.basis()).isEqualTo("employee_service_category");
        assertThat(prediction.p50().toSeconds()).isEqualTo(123);
    }

    @Test
    @DisplayName("незнакомая модели услуга уходит на каскад, а знакомая считается по модели")
    void unknownServiceFallsBackWhileKnownOneUsesTheModel() {
        stats(SEND, E1, "default", 400, 10);
        publishModel(MODEL);                                            // модель знает только parcel

        assertThat(durations.predict(SEND, "default", E1, 11, W1).basis()).isEqualTo("employee_service_category");
        assertThat(durations.predict(PARCEL, "default", E1, 11, W1).modelVersion()).isEqualTo("dm-it");
    }
}
