package honey.road.center.ingest;

import honey.road.contracts.event.Subjects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * One {@code events} row from a {@code CENTER_EVT} message. Everything that could make the multi-row insert fail is
 * parsed here, before the database: one malformed event must not stop the batch it arrived with.
 */
record EventRow(UUID eventId, String branchId, String eventType, @Nullable String aggregateType,
                @Nullable UUID aggregateId, Instant occurredAt, String payload) {

    /** Not an event the center can store; acknowledged as {@code term} so it is never redelivered. */
    static final class Malformed extends RuntimeException {
        Malformed(String message) { super(message); }
    }

    static EventRow parse(JsonMapper json, String subject, byte[] data, Instant now) {
        var body = new String(data, StandardCharsets.UTF_8);
        JsonNode e;
        try {
            e = json.readTree(body);
        } catch (RuntimeException ex) {
            throw new Malformed("not JSON on " + subject);
        }
        var id = e.path("event_id").asString(null);
        if (id == null) {
            throw new Malformed("event without event_id on " + subject);
        }
        try {
            var aggregateId = e.path("aggregate_id").asString(null);
            var occurred = e.path("occurred_at").asString(null);
            return new EventRow(UUID.fromString(id),
                    e.path("branch_id").asString(Subjects.branchIdOf(subject)),
                    e.path("event_type").asString("unknown"),
                    e.path("aggregate_type").asString(null),
                    aggregateId == null ? null : UUID.fromString(aggregateId),
                    occurred == null ? now : Instant.parse(occurred),
                    body);
        } catch (RuntimeException ex) {
            throw new Malformed("bad field in event " + id + " on " + subject + ": " + ex.getMessage());
        }
    }
}
