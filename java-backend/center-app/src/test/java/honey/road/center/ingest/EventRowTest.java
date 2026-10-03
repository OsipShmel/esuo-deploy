package honey.road.center.ingest;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** C3a: what reaches the multi-row insert has already been checked, so one bad event cannot fail its batch. */
class EventRowTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-10-05T09:00:00Z");
    private static final String SUBJECT = "branch.b01.evt.ticket.created";

    private static EventRow parse(String body) {
        return EventRow.parse(JSON, SUBJECT, body.getBytes(StandardCharsets.UTF_8), NOW);
    }

    @Test
    void envelopeBecomesARow() {
        var id = UUID.randomUUID();
        var agg = UUID.randomUUID();
        var body = """
                {"event_id":"%s","event_type":"ticket.created","branch_id":"b01","aggregate_type":"ticket",
                 "aggregate_id":"%s","occurred_at":"2026-10-05T08:59:58Z","data":{"status":"waiting"}}""".formatted(id, agg);
        var row = parse(body);
        assertThat(row.eventId()).isEqualTo(id);
        assertThat(row.aggregateId()).isEqualTo(agg);
        assertThat(row.occurredAt()).isEqualTo(Instant.parse("2026-10-05T08:59:58Z"));
        assertThat(row.payload()).isEqualTo(body);
    }

    @Test
    void missingOptionalFieldsFallBackLikeBefore() {
        var row = parse("{\"event_id\":\"%s\"}".formatted(UUID.randomUUID()));
        assertThat(row.branchId()).isEqualTo("b01");               // from the subject
        assertThat(row.eventType()).isEqualTo("unknown");
        assertThat(row.aggregateId()).isNull();
        assertThat(row.occurredAt()).isEqualTo(NOW);
    }

    @Test
    void malformedEventsAreRejectedBeforeTheDatabase() {
        assertThatThrownBy(() -> parse("not json")).isInstanceOf(EventRow.Malformed.class);
        assertThatThrownBy(() -> parse("{\"event_type\":\"x\"}")).isInstanceOf(EventRow.Malformed.class);
        assertThatThrownBy(() -> parse("{\"event_id\":\"nope\"}")).isInstanceOf(EventRow.Malformed.class);
        assertThatThrownBy(() -> parse("{\"event_id\":\"%s\",\"aggregate_id\":\"x\"}".formatted(UUID.randomUUID())))
                .isInstanceOf(EventRow.Malformed.class);
        assertThatThrownBy(() -> parse("{\"event_id\":\"%s\",\"occurred_at\":\"yesterday\"}".formatted(UUID.randomUUID())))
                .isInstanceOf(EventRow.Malformed.class);
    }
}
