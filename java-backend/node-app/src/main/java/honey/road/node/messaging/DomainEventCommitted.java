package honey.road.node.messaging;

import honey.road.contracts.event.EventEnvelope;

import java.util.UUID;

/**
 * Spring-событие «в транзакции записано доменное событие». Слушатели подписываются через
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} и получают его только после коммита.
 */
public record DomainEventCommitted(EventEnvelope envelope, UUID publicToken) {
}
