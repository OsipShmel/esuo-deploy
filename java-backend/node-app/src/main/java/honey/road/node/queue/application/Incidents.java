package honey.road.node.queue.application;

import honey.road.contracts.event.Actor;
import honey.road.contracts.event.EventTypes;
import honey.road.node.config.NodeProperties;
import honey.road.node.persistence.EventRepository;
import honey.road.node.persistence.TicketRepository;
import honey.road.node.persistence.WindowRepository;
import honey.road.node.queue.domain.DomainException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

/**
 * Incidents (sect 5.1, 9.3): a problem reported by an operator and closed by the admin; open ones show up in
 * analytics deviations.
 */
@Service
public class Incidents {
    private static final Set<String> KINDS = Set.of("technical", "operational");
    private static final int MAX_DESCRIPTION = 1000;

    public record IncidentView(UUID id, String kind, String description, UUID windowId, UUID ticketId,
                               UUID reportedBy, String status, Instant createdAt, Instant resolvedAt) {
    }

    private final JdbcClient jdbc;
    private final WindowRepository windows;
    private final TicketRepository tickets;
    private final EventRepository events;
    private final UuidGenerator ids;
    private final NodeProperties node;
    private final Clock clock;

    public Incidents(JdbcClient jdbc, WindowRepository windows, TicketRepository tickets, EventRepository events,
                     UuidGenerator ids, NodeProperties node, Clock clock) {
        this.jdbc = jdbc; this.windows = windows; this.tickets = tickets; this.events = events;
        this.ids = ids; this.node = node; this.clock = clock;
    }

    @Transactional
    public IncidentView create(String kind, String description, UUID windowId, UUID ticketId, UUID employeeId, Actor actor) {
        if (kind == null || !KINDS.contains(kind)) {
            throw DomainException.validation("kind: technical | operational");
        }
        if (description == null || description.isBlank() || description.length() > MAX_DESCRIPTION) {
            throw DomainException.validation("Описание обязательно, до " + MAX_DESCRIPTION + " символов");
        }
        if (windowId != null) { windows.get(node.branchId(), windowId); }          // 404 if foreign or missing
        if (ticketId != null) { tickets.byId(node.branchId(), ticketId, false); }
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        var id = ids.next(now);
        jdbc.sql("""
                        INSERT INTO incidents(id, window_id, ticket_id, kind, description, reported_by, status, created_at)
                        VALUES (:id, :w, :t, :k, :d, :e, 'open', :now)
                        """)
                .param("id", id).param("w", windowId).param("t", ticketId).param("k", kind)
                .param("d", description.strip()).param("e", employeeId).param("now", Timestamp.from(now)).update();

        var data = new LinkedHashMap<String, Object>();
        data.put("incident_id", id);
        data.put("kind", kind);
        data.put("description", description.strip());
        data.put("window_id", windowId);
        data.put("ticket_id", ticketId);
        events.otherEvent(node.branchId(), EventTypes.AGGREGATE_INCIDENT, id, 1, EventTypes.INCIDENT_CREATED,
                actor, now, null, data);
        return get(id);
    }

    /** Closes an incident; idempotent. */
    @Transactional
    public IncidentView resolve(UUID id) {
        get(id);
        jdbc.sql("UPDATE incidents SET status='resolved', resolved_at=:now WHERE id=:id AND status='open'")
                .param("now", Timestamp.from(clock.instant())).param("id", id).update();
        return get(id);
    }

    public IncidentView get(UUID id) {
        return jdbc.sql("SELECT * FROM incidents WHERE id=:id").param("id", id)
                .query((rs, n) -> new IncidentView(rs.getObject("id", UUID.class), rs.getString("kind"),
                        rs.getString("description"), rs.getObject("window_id", UUID.class), rs.getObject("ticket_id", UUID.class),
                        rs.getObject("reported_by", UUID.class), rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant()))
                .optional().orElseThrow(() -> new DomainException("NOT_FOUND", "Инцидент не найден"));
    }
}
