package honey.road.node.messaging.outbox;

import java.time.Duration;

/** Лаг outbox: сколько событий ждёт публикации и возраст самого старого. */
public record OutboxStats(long pending, Duration oldestAge) {
}
