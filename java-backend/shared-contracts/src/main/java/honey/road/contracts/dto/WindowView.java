package honey.road.contracts.dto;

import java.util.List;
import java.util.UUID;

/** Window state for operator and admin UIs. {@code activeTicket}: ticket in called/serving. */
public record WindowView(UUID id, String name, String status, UUID employeeId, List<UUID> serviceIds,
                         TicketView activeTicket, int version) {
}
