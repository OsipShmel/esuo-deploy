package honey.road.node.queue.application;

import honey.road.node.config.JsonCodec;
import honey.road.node.config.NodeProperties;
import honey.road.node.persistence.CatalogRepository;
import honey.road.node.persistence.TicketRepository;
import honey.road.node.queue.domain.DomainException;
import honey.road.node.queue.domain.PostalService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Operator reads: queue with source and deadline, ticket journal. */
@Service
@Transactional(readOnly = true)
public class OperatorQueries {
    private static final Set<String> ACTIVE = Set.of("booked", "waiting", "called", "serving");

    private final TicketRepository tickets;
    private final CatalogRepository catalog;
    private final NodeProperties node;
    private final JdbcClient jdbc;
    private final JsonCodec json;
    private final Clock clock;

    public OperatorQueries(TicketRepository tickets, CatalogRepository catalog, NodeProperties node,
                           JdbcClient jdbc, JsonCodec json, Clock clock) {
        this.tickets = tickets; this.catalog = catalog; this.node = node; this.jdbc = jdbc; this.json = json; this.clock = clock;
    }

    /** Operator queue item: no client token; source, deadline and position. */
    public record QueueItem(UUID ticketId, String displayNumber, UUID serviceId, String serviceName, String source,
                            String clientCategory, String status, Instant issuedAt, Instant slotStart,
                            Instant eligibleFrom, Instant dueAt, boolean overdue, Integer position,
                            UUID windowId, UUID targetWindowId, Instant calledAt, int callCount, int version) {
    }

    public List<QueueItem> queue(UUID serviceId, String status) {
        if (status != null && !ACTIVE.contains(status)) {
            throw DomainException.validation("status: booked | waiting | called | serving");
        }
        var zone = ZoneId.of(catalog.branch(node.branchId()).timezone());
        var today = LocalDate.now(clock.withZone(zone));
        var services = catalog.services(node.branchId()).stream()
                .collect(Collectors.toMap(PostalService::id, Function.identity()));
        var now = clock.instant();
        var statuses = status == null ? ACTIVE : Set.of(status);
        // booked: today's bookings only
        var rows = tickets.byStatuses(node.branchId(), statuses).stream()
                .filter(t -> !"booked".equals(t.status().name()) || t.slotStart().atZone(zone).toLocalDate().equals(today))
                .toList();
        var positions = new HashMap<UUID, Integer>();
        var result = new ArrayList<QueueItem>();
        rows.stream()
                .filter(t -> serviceId == null || serviceId.equals(t.serviceId()))
                .sorted((a, b) -> {
                    if (a.dueAt() == null || b.dueAt() == null) { return Long.compare(a.seq(), b.seq()); }
                    int c = a.dueAt().compareTo(b.dueAt());
                    return c != 0 ? c : Long.compare(a.seq(), b.seq());
                })
                .forEach(t -> {
                    Integer position = null;
                    if ("waiting".equals(t.status().name())) {
                        position = positions.merge(t.serviceId(), 1, Integer::sum) - 1;
                    }
                    var s = services.get(t.serviceId());
                    result.add(new QueueItem(t.id(), t.displayNumber(), t.serviceId(), s == null ? null : s.name(),
                            t.source().name(), t.clientCategory(), t.status().name(), t.issuedAt(), t.slotStart(),
                            t.eligibleFrom(), t.dueAt(), t.dueAt() != null && !t.dueAt().isAfter(now) && "waiting".equals(t.status().name()),
                            position, t.windowId(), t.targetWindowId(), t.calledAt(), t.callCount(), t.version()));
                });
        return result;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> events(UUID ticketId) {
        tickets.byId(node.branchId(), ticketId, false);                   // 404 for foreign/unknown
        return jdbc.sql("""
                        SELECT id, event_type, from_status, to_status, actor_type, actor_id, payload::text AS payload, occurred_at
                          FROM ticket_events WHERE ticket_id=:t ORDER BY occurred_at, id
                        """)
                .param("t", ticketId)
                .query((rs, n) -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("event_id", rs.getObject("id", UUID.class));
                    m.put("event_type", rs.getString("event_type"));
                    m.put("from_status", rs.getString("from_status"));
                    m.put("to_status", rs.getString("to_status"));
                    m.put("actor_type", rs.getString("actor_type"));
                    m.put("actor_id", rs.getString("actor_id"));
                    m.put("occurred_at", rs.getTimestamp("occurred_at").toInstant());
                    m.put("data", json.readValue(rs.getString("payload"), Map.class));
                    return (Map<String, Object>) m;
                }).list();
    }
}
