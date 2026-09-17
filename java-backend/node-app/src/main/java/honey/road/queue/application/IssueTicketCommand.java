package honey.road.queue.application;

import honey.road.queue.domain.TicketSource;
import java.time.Instant;
import java.util.UUID;

public record IssueTicketCommand(TicketSource source, UUID serviceId, Instant slotStart,
                                 String clientCategory, String deviceId, UUID windowId) {}
