package honey.road.node.queue.application;

import honey.road.contracts.event.Actor;
import honey.road.contracts.event.EventTypes;
import honey.road.contracts.event.TokenHash;
import honey.road.contracts.inbox.MpAppointmentMessage;
import honey.road.node.config.NodeProperties;
import honey.road.node.persistence.CatalogRepository;
import honey.road.node.persistence.EventRepository;
import honey.road.node.persistence.PolicyRepository;
import honey.road.node.queue.domain.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.time.Instant;

/**
 * MP booking (sect 4.6): creates a {@code booked} ticket via the same {@link IssueTicket} as web booking and
 * writes the result in the same transaction: {@code mp.appointment.confirmed} or {@code mp.appointment.rejected}
 * (reason = domain error code: {@code SLOT_EXPIRED}, {@code SLOT_UNAVAILABLE}, {@code SERVICE_NOT_FOUND}...).
 * Idempotent by {@code mp_appointment_id} (unique): a redelivery returns the same ticket.
 */
@Service
public class MpAppointmentHandler {
    private final IssueTicket issue;
    private final CatalogRepository catalog;
    private final EventRepository events;
    private final PolicyRepository policies;
    private final NodeProperties node;
    private final Clock clock;

    public MpAppointmentHandler(IssueTicket issue, CatalogRepository catalog, EventRepository events,
                                PolicyRepository policies, NodeProperties node, Clock clock) {
        this.issue = issue; this.catalog = catalog; this.events = events; this.policies = policies;
        this.node = node; this.clock = clock;
    }

    /**
     * Creates the ticket and the "confirmed" event. A rule violation throws {@link DomainException}: everything rolls
     * back and {@link #reject} records the rejection.
     */
    @Transactional
    public void confirm(MpAppointmentMessage m) {
        validate(m);
        var service = catalog.services(node.branchId()).stream()
                .filter(s -> s.code().equals(m.serviceCode())).findFirst()
                .orElseThrow(() -> new DomainException("SERVICE_NOT_FOUND", "Нет услуги с кодом " + m.serviceCode()));
        var view = issue.fromMobileAppointment(m.mpAppointmentId(), service.id(), m.slotStart(), m.clientCategory());

        var data = base(m);
        data.put("ticket_id", view.ticketId());
        data.put("display_number", view.displayNumber());
        data.put("token_hash", TokenHash.of(view.token()));
        data.put("status", view.status());
        events.otherEvent(node.branchId(), EventTypes.AGGREGATE_MP, aggregateId(m), 1,
                EventTypes.MP_APPOINTMENT_CONFIRMED, Actor.center(), now(), policies.current().configVersion(), data);
    }

    /** Rejection result in a separate transaction after {@link #confirm} rolled back. */
    @Transactional
    public void reject(MpAppointmentMessage m, String code, String detail) {
        var data = base(m);
        data.put("reason", code);
        data.put("detail", detail);
        events.otherEvent(node.branchId(), EventTypes.AGGREGATE_MP, aggregateId(m), 1,
                EventTypes.MP_APPOINTMENT_REJECTED, Actor.center(), now(), policies.current().configVersion(), data);
    }

    private static void validate(MpAppointmentMessage m) {
        if (m == null || m.mpAppointmentId() == null || m.mpAppointmentId().isBlank() || m.serviceCode() == null
                || m.slotStart() == null) {
            throw DomainException.validation("Запись МП: mp_appointment_id, service_code и slot_start обязательны");
        }
    }

    private static LinkedHashMap<String, Object> base(MpAppointmentMessage m) {
        var data = new LinkedHashMap<String, Object>();
        data.put("mp_appointment_id", m.mpAppointmentId());
        data.put("service_code", m.serviceCode());
        data.put("slot_start", m.slotStart());
        data.put("client_ref", m.clientRef());
        return data;
    }

    private static UUID aggregateId(MpAppointmentMessage m) {
        return UUID.nameUUIDFromBytes(("mp:" + m.mpAppointmentId()).getBytes(StandardCharsets.UTF_8));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
