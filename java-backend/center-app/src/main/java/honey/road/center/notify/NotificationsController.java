package honey.road.center.notify;

import honey.road.center.dlq.DeadLetters;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Notification delivery status for the network monitor (sect 9.5). */
@RestController
class NotificationsController {
    private final NotificationSender sender;
    private final NotificationLog log;
    private final DeadLetters dlq;

    NotificationsController(NotificationSender sender, NotificationLog log, DeadLetters dlq) {
        this.sender = sender; this.log = log; this.dlq = dlq;
    }

    record BreakerView(String state, float failureRate) {
    }

    record Status(BreakerView circuitBreaker, Map<String, Long> counts, @Nullable String lastError) {
    }

    @GetMapping("/api/v1/admin/notifications/status")
    Status status() {
        var cb = sender.breaker();
        var counts = new LinkedHashMap<>(log.counts());
        counts.put("dlq_open", dlq.countOpen(DeadLetters.NOTIFICATION));
        return new Status(new BreakerView(cb.getState().name(), cb.getMetrics().getFailureRate()), counts, log.lastError());
    }
}
