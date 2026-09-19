package honey.road.contracts.inbox;

import java.time.Instant;

/**
 * Запись из МП, доставленная центром в отделение ({@code center.mp.inbox.branch.{b}}), разделы 4.6 и 11.2.
 * {@code mpAppointmentId} уникален и совпадает с {@code Nats-Msg-Id}; услуга задаётся кодом
 * ({@code services.code}), потому что идентификаторы услуг принадлежат отделению.
 */
public record MpAppointmentMessage(
        String mpAppointmentId,
        String serviceCode,
        Instant slotStart,
        String clientCategory,
        String clientRef
) {
}
