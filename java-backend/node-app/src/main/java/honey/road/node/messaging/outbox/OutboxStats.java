package honey.road.node.messaging.outbox;

import java.time.Duration;

/** Outbox lag: pending events and age of the oldest. */
public record OutboxStats(long pending, Duration oldestAge) {
}
