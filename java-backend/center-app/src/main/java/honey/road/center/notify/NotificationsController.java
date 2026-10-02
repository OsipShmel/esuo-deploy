package honey.road.center.notify;

import honey.road.center.dlq.DeadLetters;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
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

    /** @param failureRate over the last {@code windowCalls} calls; negative while there are fewer calls than needed */
    record BreakerView(String state, float failureRate, int windowCalls) {
    }

    /** Breaker — now; counts and the last error — since the center database was created. */
    record Status(BreakerView circuitBreaker, Map<String, Long> counts, @Nullable String lastError,
                  @Nullable Instant lastErrorAt) {
    }

    @GetMapping("/api/v1/admin/notifications/status")
    Status status() {
        var cb = sender.breaker();
        var counts = new LinkedHashMap<>(log.counts());
        counts.put("dlq_open", dlq.countOpen(DeadLetters.NOTIFICATION));
        var last = log.lastError();
        return new Status(new BreakerView(cb.getState().name(), cb.getMetrics().getFailureRate(),
                cb.getCircuitBreakerConfig().getSlidingWindowSize()), counts,
                last == null ? null : last.error(), last == null ? null : last.at());
    }
}
