package honey.road.queue.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record Ticket(
        UUID id, UUID publicToken, String branchId, UUID serviceId,
        honey.road.queue.domain.TicketSource source, honey.road.queue.domain.TicketStatus status, String clientCategory,
        int configVersion, long seq, String displayNumber, Instant issuedAt, LocalDate issuedDay,
        Instant slotStart, Instant slotEnd, Instant eligibleFrom, Instant dueAt,
        UUID windowId, UUID employeeId, UUID targetWindowId, String deviceId, String mpAppointmentId,
        Instant updatedAt, int version, Instant calledAt) {}
