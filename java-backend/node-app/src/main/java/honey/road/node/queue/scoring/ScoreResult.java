package honey.road.node.queue.scoring;

import java.time.Duration;

/**
 * @param value             больше — лучше
 * @param predictedDuration прогноз времени обслуживания (для ETA)
 * @param strategy          uniform | heuristic | ...
 * @param modelVersion      версия модели/таблицы статистики
 * @param basis             на чём основан прогноз: уровень каскада ({@code employee_service_category}, …, {@code fallback})
 * @param samples           число наблюдений за этим уровнем (0 у {@code fallback} и у uniform)
 */
public record ScoreResult(double value, Duration predictedDuration, String strategy, String modelVersion,
                          String basis, int samples) {
    public ScoreResult(double value, Duration predictedDuration, String strategy, String modelVersion) {
        this(value, predictedDuration, strategy, modelVersion, "const", 0);
    }
}
