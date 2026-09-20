package honey.road.node.queue.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Snapshot of a {@code tickets} row; changed only via application-layer commands. */
public record Ticket(
        UUID id, UUID publicToken, String branchId, UUID serviceId,
        TicketSource source, TicketStatus status, String clientCategory,
        int configVersion, long seq, String displayNumber, Instant issuedAt, LocalDate issuedDay,
        Instant slotStart, Instant slotEnd, Instant eligibleFrom, Instant dueAt,
        UUID windowId, UUID employeeId, UUID targetWindowId, String deviceId, String mpAppointmentId,
        Instant updatedAt, int version, Instant calledAt,
        int callCount, Instant checkedInAt, Instant servingStartedAt, Instant completedAt, int postponeCount) {

    /** New ticket constructor (all counters zero). */
    public static Ticket fresh(UUID id, UUID publicToken, String branchId, UUID serviceId, TicketSource source,
                               TicketStatus status, String clientCategory, int configVersion, String displayNumber,
                               Instant issuedAt, LocalDate issuedDay, Instant slotStart, Instant slotEnd,
                               Instant eligibleFrom, Instant dueAt, String deviceId, String mpAppointmentId) {
        return new Ticket(id, publicToken, branchId, serviceId, source, status, clientCategory, configVersion, 0,
                displayNumber, issuedAt, issuedDay, slotStart, slotEnd, eligibleFrom, dueAt, null, null, null,
                deviceId, mpAppointmentId, issuedAt, 0, null, 0, null, null, null, 0);
    }

    /** Copy with another {@code seq} (tests, after INSERT). */
    public Ticket withSeq(long newSeq) {
        return new Ticket(id, publicToken, branchId, serviceId, source, status, clientCategory, configVersion, newSeq,
                displayNumber, issuedAt, issuedDay, slotStart, slotEnd, eligibleFrom, dueAt, windowId, employeeId,
                targetWindowId, deviceId, mpAppointmentId, updatedAt, version, calledAt, callCount, checkedInAt,
                servingStartedAt, completedAt, postponeCount);
    }

    public boolean active() {
        return status == TicketStatus.called || status == TicketStatus.serving;
    }
}
