package honey.road.node.queue.scoring;

import java.time.Duration;

/**
 * @param value             больше — лучше
 * @param predictedDuration прогноз времени обслуживания (для ETA)
 * @param strategy          uniform | heuristic | ...
 * @param modelVersion      версия модели/таблицы статистики
 */
public record ScoreResult(double value, Duration predictedDuration, String strategy, String modelVersion) {
}
