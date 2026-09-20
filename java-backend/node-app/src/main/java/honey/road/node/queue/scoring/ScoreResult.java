package honey.road.node.queue.scoring;

import java.time.Duration;

/**
 * @param value             higher is better
 * @param predictedDuration predicted service time (ETA)
 * @param strategy          uniform | heuristic | ...
 * @param modelVersion      model / stats table version
 * @param basis             cascade level of the estimate ({@code employee_service_category}, …, {@code fallback})
 * @param samples           samples at that level (0 for {@code fallback} and uniform)
 */
public record ScoreResult(double value, Duration predictedDuration, String strategy, String modelVersion,
                          String basis, int samples) {
    public ScoreResult(double value, Duration predictedDuration, String strategy, String modelVersion) {
        this(value, predictedDuration, strategy, modelVersion, "const", 0);
    }
}
