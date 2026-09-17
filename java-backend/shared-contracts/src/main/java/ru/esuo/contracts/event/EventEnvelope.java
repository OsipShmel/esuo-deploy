package ru.esuo.contracts.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
* Event envelope (Section 10.2). Written by the domain to {@code outbox.payload},
* published by the outbox worker with {@code Nats-Msg-Id = eventId}.
* Consumers deduplicate based on {@code eventId}.
* The SSE hub routes based on {@code aggregateId}.
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
    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(branchId, "branchId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (data != null && data.containsKey("public_token")) {
            throw new IllegalArgumentException("public_token must not leave the branch in events");
        }
        //TODO! – ?
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    /** {@code branch.b01.evt.ticket.called} */
    public String subject() {
        return "branch." + branchId + ".evt." + eventType;
    }
}
