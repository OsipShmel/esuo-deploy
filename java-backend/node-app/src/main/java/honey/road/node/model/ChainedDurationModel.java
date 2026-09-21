package honey.road.node.model;

import honey.road.node.persistence.HandlingStatsRepository;
import honey.road.node.persistence.PolicyRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * The fallback chain of ml-model.md 5.3: model coefficients → {@code handling_stats} cascade →
 * {@code default-service-duration-sec}.
 *
 * <p>The chain is the reason the branch is safe. With no {@code model} section published — which is the state of
 * a fresh branch and of every stand until someone publishes one — every call lands on the cascade and returns
 * exactly what the node returned before this class existed. Turning the model off is therefore not a rollback
 * but the default; {@code enabled: false} in the section returns to it at any moment without a restart.
 */
@Component
public class ChainedDurationModel implements DurationModel {
    static final String STATS_VERSION = "handling_stats-v1";
    static final String DEFAULT_VERSION = "const";

    private final ModelSource models;
    private final HandlingStatsRepository stats;
    private final PolicyRepository policies;

    public ChainedDurationModel(ModelSource models, HandlingStatsRepository stats, PolicyRepository policies) {
        this.models = models;
        this.stats = stats;
        this.policies = policies;
    }

    @Override
    public Prediction predict(UUID serviceId, String category, UUID employeeId, int hourOfDay, UUID windowId) {
        var policy = policies.current();
        var params = models.current();

        if (params.usable()) {
            var code = models.serviceCode(serviceId);
            var prediction = params.predict(code, category, id(employeeId), hourOfDay, id(windowId));
            if (prediction != null) {
                return prediction;
            }
        }

        // Каскад средних: ровно то, что нода считала до появления модели (sect 16.2).
        // `basis` — это уровень каскада (`employee_service_category`, …, `fallback`), а не общее слово:
        // журнал вызовов и раньше называл уровень, и по нему видно, на чём основан прогноз.
        var forecast = stats.forecast(serviceId, employeeId, category, policy.heuristicMinSamples());
        if (forecast.isPresent()) {
            return flat(forecast.get().seconds(), forecast.get().level(), STATS_VERSION, forecast.get().samples());
        }
        return flat(policy.defaultServiceDurationSec(), HandlingStatsRepository.LEVEL_FALLBACK, DEFAULT_VERSION, 0);
    }

    /**
     * The cascade has an average and no spread, so mean, p50 and p90 coincide. That is not a simplification but
     * the honest shape of that estimate: an interval cannot be derived from a mean, which is exactly why the
     * client sees a single time on this path and a range on the model path (ml-model.md, раздел 4 отчёта).
     */
    private static Prediction flat(double seconds, String basis, String version, int samples) {
        var duration = Duration.ofMillis(Math.max(1, Math.round(Math.max(1.0, seconds) * 1000)));
        return new Prediction(duration, duration, duration, basis, version, samples);
    }

    private static String id(UUID value) {
        return value == null ? null : value.toString();
    }

    /** Model state for {@code GET /admin/model} and for the model card on the admin screen. */
    public ModelParams params() {
        return models.current();
    }
}
