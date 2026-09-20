package honey.road.center.notify;

import honey.road.center.config.CenterProperties;
import honey.road.center.dlq.DeadLetterRetrier;
import honey.road.center.dlq.DeadLetters;
import honey.road.center.integration.Breakers;
import honey.road.center.network.BranchRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * One delivery attempt of a {@code notification.requested} event (sect 4.7, 11.3): dedup by {@code dedup_key},
 * TTL check, circuit breaker, provider call, {@code notification_log}. Retries and DLQ are decided by the caller.
 */
@Service
public class NotificationSender implements DeadLetterRetrier {

    public enum Result { SENT, DUPLICATE, EXPIRED, REJECTED, UNAVAILABLE, CIRCUIT_OPEN }

    public record Attempt(Result result, @Nullable String error) {
    }

    private final NotificationGateway gateway;
    private final NotificationLog log;
    private final BranchRegistry registry;
    private final CenterProperties.Notify props;
    private final JsonMapper json;
    private final Clock clock;
    private final CircuitBreaker breaker;

    NotificationSender(NotificationGateway gateway, NotificationLog log, BranchRegistry registry, CenterProperties props,
                       JsonMapper json, Clock clock) {
        this.gateway = gateway; this.log = log; this.registry = registry; this.props = props.notify();
        this.json = json; this.clock = clock;
        this.breaker = Breakers.of("notify", this.props.circuitBreaker());
    }

    public CircuitBreaker breaker() { return breaker; }

    static String dedupKey(JsonNode event) {
        var data = event.path("data");
        return data.path("dedup_key").asString(data.path("ticket_id").asString("?") + ":" + data.path("kind").asString("unknown"));
    }

    public Attempt deliver(JsonNode event, boolean checkTtl) {
        var data = event.path("data");
        var key = dedupKey(event);
        var branchId = event.path("branch_id").asString("?");
        var eventId = uuid(event.path("event_id").asString(null));
        var now = clock.instant();
        if (log.alreadySent(key)) {
            return new Attempt(Result.DUPLICATE, null);
        }
        var occurred = event.hasNonNull("occurred_at") ? Instant.parse(event.path("occurred_at").asString()) : now;
        var expiresAt = occurred.plus(props.messageTtl());
        if (checkTtl && expiresAt.isBefore(branchNow(branchId, now))) {
            log.record(key, eventId, branchId, "expired", "TTL " + props.messageTtl() + " passed", now);
            return new Attempt(Result.EXPIRED, "TTL passed");
        }
        if (!breaker.tryAcquirePermission()) {
            log.record(key, eventId, branchId, "failed", "circuit breaker open", now);
            return new Attempt(Result.CIRCUIT_OPEN, "circuit breaker open");
        }
        long started = System.nanoTime();
        try {
            gateway.send(message(event, key, branchId, expiresAt));
            breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            return log.sent(key, eventId, branchId, clock.instant())
                    ? new Attempt(Result.SENT, null) : new Attempt(Result.DUPLICATE, null);
        } catch (NotificationGateway.NotificationRejectedException e) {
            breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);   // provider is up, the message is bad
            log.record(key, eventId, branchId, "rejected", e.getMessage(), clock.instant());
            return new Attempt(Result.REJECTED, e.getMessage());
        } catch (RuntimeException e) {
            breaker.onError(System.nanoTime() - started, TimeUnit.NANOSECONDS, e);
            log.record(key, eventId, branchId, "failed", e.getMessage(), clock.instant());
            return new Attempt(Result.UNAVAILABLE, e.getMessage());
        }
    }

    @Override
    public String kind() { return DeadLetters.NOTIFICATION; }

    /** Manual retry ignores TTL: the operator decided to send it anyway. */
    @Override
    public RetryResult retry(DeadLetters.DeadLetter letter) {
        var attempt = deliver(json.readTree(letter.payload()), false);
        return switch (attempt.result()) {
            case SENT, DUPLICATE -> RetryResult.ok();
            default -> RetryResult.failed(attempt.error());
        };
    }

    private NotificationGateway.NotificationMessage message(JsonNode event, String key, String branchId, Instant expiresAt) {
        var data = event.path("data");
        var params = new LinkedHashMap<String, String>();
        for (var field : new String[]{"display_number", "status", "position", "eta", "eta_confidence"}) {
            if (data.hasNonNull(field)) { params.put(field, data.path(field).asString()); }
        }
        var kind = data.path("kind").asString("approaching");
        return new NotificationGateway.NotificationMessage(key, branchId, uuid(data.path("ticket_id").asString(null)), "demo",
                data.path("token_hash").asString(null), "queue." + kind, params, expiresAt);
    }

    /**
     * "Now" on the branch scale (sect 11.3): a demo node runs on accelerated time, so it is estimated from the last
     * heartbeat as {@code sent_at + real elapsed × demo_rate}.
     */
    Instant branchNow(String branchId, Instant realNow) {
        return registry.get(branchId).map(b -> {
            var hb = json.readTree(b.lastHeartbeat());
            double rate = hb.path("demo_rate").asDouble(0);
            if (rate <= 0 || !hb.hasNonNull("sent_at")) { return realNow; }
            var elapsed = Duration.between(b.lastHeartbeatAt(), realNow).toMillis();
            return Instant.parse(hb.path("sent_at").asString()).plusMillis((long) (elapsed * rate));
        }).orElse(realNow);
    }

    private static @Nullable UUID uuid(@Nullable String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
