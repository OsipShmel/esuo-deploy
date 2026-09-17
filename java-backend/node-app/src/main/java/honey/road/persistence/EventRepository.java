package honey.road.persistence;

import honey.road.EventEnvelope;
import honey.road.queue.application.UuidGenerator;
import honey.road.queue.domain.Ticket;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import honey.road.config.JsonCodec;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;

@Repository
public class EventRepository {
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final UuidGenerator ids;
    public EventRepository(JdbcTemplate jdbc, JsonCodec json, UuidGenerator ids) {
        this.jdbc = jdbc; this.json = json; this.ids = ids;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(Ticket ticket, String type, String fromStatus, EventEnvelope.Actor actor) {
        var id = ids.next(ticket.updatedAt());
        var data = new LinkedHashMap<String,Object>();
        data.put("display_number",ticket.displayNumber());
        data.put("public_token",ticket.publicToken());
        data.put("source",ticket.source().name());
        data.put("service_id",ticket.serviceId());
        data.put("window_id",ticket.windowId());
        data.put("target_window_id",ticket.targetWindowId());
        data.put("status",ticket.status().name());
        data.put("client_category",ticket.clientCategory());
        data.put("slot_start",ticket.slotStart());
        data.put("eligible_from",ticket.eligibleFrom());
        data.put("due_at",ticket.dueAt());
        data.put("issued_at",ticket.issuedAt());
        data.put("updated_at",ticket.updatedAt());
        var envelope = new EventEnvelope(id,type,1,ticket.branchId(),"ticket",ticket.id(),ticket.version(),
                ticket.updatedAt(),ticket.configVersion(),actor,data);
        jdbc.update("""
                INSERT INTO ticket_events(id,ticket_id,event_type,from_status,to_status,actor_type,actor_id,payload,occurred_at)
                VALUES (?,?,?,?,?,?,?,CAST(? AS jsonb),?)
                """, id, ticket.id(), type, fromStatus, ticket.status().name(), actor.type(), actor.id(),
                json.writeValueAsString(data),Timestamp.from(ticket.updatedAt()));
        String requestId = MDC.get("request_id");
        if (requestId == null) { requestId = UUID.randomUUID().toString(); }
        var headers = Map.of("Nats-Msg-Id",id.toString(),"X-Request-Id",requestId,"Content-Type","application/json");
        jdbc.update("""
                INSERT INTO outbox(id,subject,payload,headers,status,next_attempt_at,created_at)
                VALUES (?,?,CAST(? AS jsonb),CAST(? AS jsonb),'pending',?,?)
                """, id,"branch."+ticket.branchId()+".evt."+type,json.writeValueAsString(envelope),json.writeValueAsString(headers),
                Timestamp.from(ticket.updatedAt()),Timestamp.from(ticket.updatedAt()));
    }
}
