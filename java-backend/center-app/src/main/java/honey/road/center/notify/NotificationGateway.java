package honey.road.center.notify;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Notification provider port (sect 11.1). Demo adapter: {@link HttpDemoNotificationAdapter} → notify-mock. */
public interface NotificationGateway {

    /**
     * @throws NotificationUnavailableException provider down, 5xx or timeout: retry later
     * @throws NotificationRejectedException    4xx: retrying is pointless
     */
    DeliveryResult send(NotificationMessage message);

    record NotificationMessage(
            String dedupKey,            // ticket_id:rule
            String branchId,
            UUID ticketId,
            String channel,             // "sms" | "push" | "demo"
            String recipientRef,        // reference to the recipient, never the phone number itself
            String templateCode,        // "queue.approaching" | "ticket.called"
            Map<String, String> params,
            Instant expiresAt
    ) {
    }

    record DeliveryResult(int status, String providerResponse) {
    }

    class NotificationUnavailableException extends RuntimeException {
        public NotificationUnavailableException(String message) { super(message); }
    }

    class NotificationRejectedException extends RuntimeException {
        public NotificationRejectedException(String message) { super(message); }
    }
}
