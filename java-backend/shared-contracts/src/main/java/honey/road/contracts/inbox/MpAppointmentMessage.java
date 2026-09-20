package honey.road.contracts.inbox;

import java.time.Instant;

/**
 * MP booking delivered by the center to the branch ({@code center.mp.inbox.branch.{b}}), sect 4.6, 11.2.
 * {@code mpAppointmentId} is unique and equals {@code Nats-Msg-Id}; the service is given by code
 * ({@code services.code}) because service ids belong to the branch.
 */
public record MpAppointmentMessage(
        String mpAppointmentId,
        String serviceCode,
        Instant slotStart,
        String clientCategory,
        String clientRef
) {
}
