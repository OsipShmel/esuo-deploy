package honey.road.contracts.event;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Конверт события (раздел 10.2). Домен пишет его в {@code outbox.payload},
 * outbox-воркер публикует с {@code Nats-Msg-Id = eventId}, потребители дедуплицируют по {@code eventId}.
 * <p>
 * Секрет клиента ({@code public_token}) из отделения не уходит: вместо него в {@code data}
 * кладётся {@code token_hash} ({@link TokenHash}). Центр находит талон для SSE по хешу токена клиента.
 * Значения {@code data} могут быть null (например, {@code window_id} у ожидающего талона).
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int schemaVersion,
        String branchId,
        String aggregateType,
        UUID aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        Integer configVersion,
        Actor actor,
        Map<String, Object> data
) {
    public static final int SCHEMA_VERSION = 1;

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(branchId, "branchId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (data != null && data.containsKey("public_token")) {
            throw new IllegalArgumentException("public_token must not leave the branch in events");
        }
        // Map.copyOf не принимает null-значения, а они в data законны.
        data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    /** {@code branch.b01.evt.ticket.called} */
    public String subject() {
        return Subjects.branchEvent(branchId, eventType);
    }
}
