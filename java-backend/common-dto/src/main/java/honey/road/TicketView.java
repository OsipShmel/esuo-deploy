package honey.road;

import java.time.Instant;
import java.util.UUID;

public record TicketView(
        UUID ticketId, UUID token, String branchId, String displayNumber,
        ServiceView service, String source, String clientCategory, String status,
        Integer position, Instant eta, String etaConfidence, Instant slotStart,
        WindowView window, Instant calledAt, boolean canCancel, boolean canPostpone,
        int version, Instant updatedAt) {
    public record ServiceView(UUID id, String name) {}
    public record WindowView(UUID id, String name) {}
}
