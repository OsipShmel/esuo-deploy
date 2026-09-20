package honey.road.contracts.event;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Event envelope (sect 10.2). The domain writes it to {@code outbox.payload}, the outbox worker publishes it with
 * {@code Nats-Msg-Id = eventId}, consumers dedup by {@code eventId}.
 * <p>The client secret ({@code public_token}) never leaves the branch: {@code data} carries {@code token_hash}
 * ({@link TokenHash}) instead. {@code data} values may be null (e.g. {@code window_id} of a waiting ticket).
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
        // Map.copyOf rejects nulls, which are valid in data.
        data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    /** {@code branch.b01.evt.ticket.called} */
    public String subject() {
        return Subjects.branchEvent(branchId, eventType);
    }
}
