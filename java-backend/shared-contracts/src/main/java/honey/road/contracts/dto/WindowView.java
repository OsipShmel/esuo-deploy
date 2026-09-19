package honey.road.contracts.dto;

import java.util.List;
import java.util.UUID;

/** Состояние окна для АРМ оператора и админки. {@code activeTicket} — талон в статусе called/serving. */
public record WindowView(UUID id, String name, String status, UUID employeeId, List<UUID> serviceIds,
                         TicketView activeTicket, int version) {
}
