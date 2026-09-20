package honey.road.node.messaging;

import honey.road.contracts.event.EventEnvelope;

import java.util.UUID;

/**
 * Spring event "a domain event was written in the transaction"; delivered to
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} listeners.
 */
public record DomainEventCommitted(EventEnvelope envelope, UUID publicToken) {
}
