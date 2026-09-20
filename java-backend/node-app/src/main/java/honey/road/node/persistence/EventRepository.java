package honey.road.node.persistence;

import honey.road.contracts.event.Actor;
import honey.road.contracts.event.EventEnvelope;
import honey.road.contracts.event.EventTypes;
import honey.road.contracts.event.TokenHash;
import honey.road.contracts.http.EsuoHeaders;
import honey.road.node.config.JsonCodec;
import honey.road.node.messaging.DomainEventCommitted;
import honey.road.node.queue.application.UuidGenerator;
import honey.road.node.queue.domain.ServiceWindow;
import honey.road.node.queue.domain.Ticket;
import honey.road.node.queue.domain.TicketStatus;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The only writer of the journal and the outbox (rule 0.3.3); called inside the command transaction only.
 * <ul>
 *   <li>{@code ticket_events}/{@code window_events}: branch journal;</li>
 *   <li>{@code outbox}: what the outbox worker publishes to {@code BRANCH_EVT};</li>
 *   <li>{@link DomainEventCommitted}: delivered to the SSE hub after commit.</li>
 * </ul>
 * The client secret never reaches the journal or the outbox, only {@code token_hash}.
 */
@Repository
public class EventRepository {
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final UuidGenerator ids;
    private final ApplicationEventPublisher publisher;

    public EventRepository(JdbcTemplate jdbc, JsonCodec json, UuidGenerator ids, ApplicationEventPublisher publisher) {
        this.jdbc = jdbc; this.json = json; this.ids = ids; this.publisher = publisher;
    }

    /** Day 1 compatibility. */
    @Transactional(propagation = Propagation.MANDATORY)
    public EventEnvelope append(Ticket ticket, String type, String fromStatus, Actor actor) {
        return ticketEvent(ticket, type, fromStatus, actor, Map.of());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public EventEnvelope ticketEvent(Ticket ticket, String type, TicketStatus from, Actor actor, Map<String, Object> extra) {
        return ticketEvent(ticket, type, from == null ? null : from.name(), actor, extra);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public EventEnvelope ticketEvent(Ticket ticket, String type, String fromStatus, Actor actor, Map<String, Object> extra) {
        var data = new LinkedHashMap<String, Object>();
        data.put("display_number", ticket.displayNumber());
        data.put("token_hash", TokenHash.of(ticket.publicToken()));
        data.put("source", ticket.source().name());
        data.put("service_id", ticket.serviceId());
        data.put("window_id", ticket.windowId());
        data.put("target_window_id", ticket.targetWindowId());
        data.put("from_status", fromStatus);
        data.put("status", ticket.status().name());
        data.put("client_category", ticket.clientCategory());
        data.put("slot_start", ticket.slotStart());
        data.put("eligible_from", ticket.eligibleFrom());
        data.put("due_at", ticket.dueAt());
        data.put("issued_at", ticket.issuedAt());
        data.put("called_at", ticket.calledAt());
        data.put("call_count", ticket.callCount());
        if (extra != null) { data.putAll(extra); }

        var id = ids.next(ticket.updatedAt());
        var envelope = new EventEnvelope(id, type, EventEnvelope.SCHEMA_VERSION, ticket.branchId(),
                EventTypes.AGGREGATE_TICKET, ticket.id(), ticket.version(), ticket.updatedAt(), ticket.configVersion(), actor, data);
        jdbc.update("""
                INSERT INTO ticket_events(id,ticket_id,event_type,from_status,to_status,actor_type,actor_id,payload,occurred_at)
                VALUES (?,?,?,?,?,?,?,CAST(? AS jsonb),?)
                """, id, ticket.id(), type, fromStatus, ticket.status().name(), actor.type(), actor.id(),
                json.writeValueAsString(data), Timestamp.from(ticket.updatedAt()));
        enqueue(envelope, ticket.publicToken());
        return envelope;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public EventEnvelope windowEvent(ServiceWindow window, String type, Actor actor, Instant now, Map<String, Object> extra) {
        var data = new LinkedHashMap<String, Object>();
        data.put("window_id", window.id());
        data.put("window_name", window.name());
        data.put("status", window.status());
        data.put("employee_id", window.currentEmployeeId());
        if (extra != null) { data.putAll(extra); }
        var id = ids.next(now);
        var envelope = new EventEnvelope(id, type, EventEnvelope.SCHEMA_VERSION, window.branchId(),
                EventTypes.AGGREGATE_WINDOW, window.id(), window.version(), now, null, actor, data);
        jdbc.update("""
                INSERT INTO window_events(id,window_id,event_type,actor_id,payload,occurred_at)
                VALUES (?,?,?,?,CAST(? AS jsonb),?)
                """, id, window.id(), type, actor.id(), json.writeValueAsString(data), Timestamp.from(now));
        enqueue(envelope, null);
        return envelope;
    }

    /** Event without a ticket/window journal entry (MP result, notification request, config.applied). */
    @Transactional(propagation = Propagation.MANDATORY)
    public EventEnvelope otherEvent(String branchId, String aggregateType, UUID aggregateId, long version,
                                    String type, Actor actor, Instant now, Integer configVersion, Map<String, Object> data) {
        return otherEvent(branchId, aggregateType, aggregateId, version, type, actor, now, configVersion, data, null);
    }

    /**
     * Same, with the ticket owner's token so the node SSE hub delivers it to that page
     * (browser notification works without the center, sect 4.7). The token is not written to the outbox.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public EventEnvelope otherEvent(String branchId, String aggregateType, UUID aggregateId, long version,
                                    String type, Actor actor, Instant now, Integer configVersion, Map<String, Object> data,
                                    UUID publicToken) {
        var envelope = new EventEnvelope(ids.next(now), type, EventEnvelope.SCHEMA_VERSION, branchId,
                aggregateType, aggregateId, version, now, configVersion, actor, data);
        enqueue(envelope, publicToken);
        return envelope;
    }

    private void enqueue(EventEnvelope envelope, UUID publicToken) {
        String requestId = MDC.get(EsuoHeaders.MDC_REQUEST_ID);
        if (requestId == null) { requestId = envelope.eventId().toString(); }
        var headers = Map.of(EsuoHeaders.NATS_MSG_ID, envelope.eventId().toString(),
                EsuoHeaders.REQUEST_ID, requestId, EsuoHeaders.CONTENT_TYPE, "application/json");
        jdbc.update("""
                INSERT INTO outbox(id,subject,payload,headers,status,next_attempt_at,created_at)
                VALUES (?,?,CAST(? AS jsonb),CAST(? AS jsonb),'pending',?,?)
                """, envelope.eventId(), envelope.subject(), json.writeValueAsString(envelope), json.writeValueAsString(headers),
                Timestamp.from(envelope.occurredAt()), Timestamp.from(envelope.occurredAt()));
        publisher.publishEvent(new DomainEventCommitted(envelope, publicToken));
    }
}
