package honey.road.node.model;

import java.time.Duration;
import java.util.UUID;

/**
 * Predicted service duration (ml-model.md, sect 5.1). The single entry point for every consumer: scoring
 * (sect 6.5), ETA (sect 6.8) and load planning (sect 6.10).
 * <p>The node does not train. Coefficients arrive from the center as the {@code model} config section and the
 * implementation only applies them; when there are none it falls back to the {@code handling_stats} cascade and
 * then to {@code default-service-duration-sec} (sect 5.3). That fallback is what makes the branch independent
 * of the trainer being reachable at all.
 */
public interface DurationModel {

    /**
     * @param serviceId  service of the ticket
     * @param category   client category
     * @param employeeId employee at the window; {@code null} when unknown (then the estimate is "as anyone")
     * @param hourOfDay  hour of the service start in the branch time zone, 0–23
     * @param windowId   window; used only when the model was trained with {@code use_window}
     */
    Prediction predict(UUID serviceId, String category, UUID employeeId, int hourOfDay, UUID windowId);

    /**
     * @param mean         expected duration; <b>this is what sums of work use</b> — ETA and hour capacity.
     *                     For a log-normal the median is lower than the mean by {@code exp(sigma^2/2)}, so adding
     *                     up medians would understate a twenty-person queue by a steady few percent (ml-model.md 3.2)
     * @param p50          median; the point forecast for <b>choosing one ticket</b>, since score only needs the
     *                     order of candidates and the median minimises absolute error
     * @param p90          upper bound of the interval shown to the client
     * @param basis        where the estimate came from: {@code model} or {@code model_partial} on the model path,
     *                     and the cascade level ({@code employee_service_category}, …, {@code fallback}) otherwise.
     *                     The cascade level is kept rather than flattened to one word because the call journal
     *                     named it before the model existed, and it is what makes a decision reviewable
     * @param modelVersion model version, or the stats table version on the fallback path
     * @param samples      observations behind the narrowest level used; 0 on the default
     */
    record Prediction(Duration mean, Duration p50, Duration p90, String basis, String modelVersion, int samples) {

        public static final String BASIS_MODEL = "model";
        public static final String BASIS_MODEL_PARTIAL = "model_partial";

        /** Confidence shown to the client (sect 6.8): how much the estimate can be trusted. */
        public String confidence() {
            if (BASIS_MODEL.equals(basis)) {
                return "high";
            }
            // Запасная цепочка без единого наблюдения — это значение по умолчанию из конфигурации,
            // и обещать по нему что-либо уверенно нельзя.
            return samples == 0 ? "low" : "medium";
        }

        public long meanSeconds() {
            return Math.max(1, mean.toSeconds());
        }

        public long p50Seconds() {
            return Math.max(1, p50.toSeconds());
        }
    }
}
