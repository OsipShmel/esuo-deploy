package honey.road;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record EventEnvelope(
        UUID eventId, String eventType, int schemaVersion, String branchId,
        String aggregateType, UUID aggregateId, int aggregateVersion,
        Instant occurredAt, int configVersion, Actor actor, Map<String, Object> data) {
    public record Actor(String type, String id) {}
}
