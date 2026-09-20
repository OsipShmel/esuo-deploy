package honey.road.center.notify;

import honey.road.center.config.CenterProperties;
import honey.road.center.dlq.DeadLetters;
import honey.road.center.nats.CenterNats;
import honey.road.center.nats.CenterStreams;
import honey.road.center.nats.DurableWorker;
import honey.road.contracts.event.EventTypes;
import io.nats.client.Message;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * {@code notify-worker} (sect 10.5): {@code branch.*.evt.notification.requested}, deliver_policy new, max_deliver 6,
 * backoff 1, 2, 5, 15, 30 s. Out of attempts, 4xx or expired TTL → {@code dead_letters} and ack.
 * An open circuit breaker counts as an attempt, so a long outage ends in the DLQ instead of looping forever.
 */
@Component
class NotifyWorker extends DurableWorker {
    private final NotificationSender sender;
    private final DeadLetters dlq;
    private final CenterProperties.Notify props;
    private final JsonMapper json;
    private final Clock clock;

    NotifyWorker(CenterNats nats, CenterStreams streams, NotificationSender sender, DeadLetters dlq, CenterProperties props,
                 JsonMapper json, Clock clock) {
        super(nats, streams, "notify-worker", "branch.*.evt." + EventTypes.NOTIFICATION_REQUESTED);
        this.sender = sender; this.dlq = dlq; this.props = props.notification(); this.json = json; this.clock = clock;
    }

    @Override
    protected ConsumerConfiguration.Builder configure(ConsumerConfiguration.Builder b) {
        return b.deliverPolicy(DeliverPolicy.New)
                .ackWait(Duration.ofSeconds(30))
                .maxDeliver(props.maxDeliver());
    }

    @Override
    protected List<Duration> backoff() { return props.backoff(); }

    @Override
    protected void handle(Message msg) {
        var body = new String(msg.getData(), StandardCharsets.UTF_8);
        var event = json.readTree(body);
        var key = NotificationSender.dedupKey(event);
        var branchId = event.path("branch_id").asString("?");
        long delivered = deliveredCount(msg);
        var attempt = sender.deliver(event, true);
        switch (attempt.result()) {
            case SENT, DUPLICATE -> dlq.resolveKey(DeadLetters.NOTIFICATION, key, clock.instant());
            case EXPIRED -> dlq.put(DeadLetters.NOTIFICATION, key, branchId, body, (int) delivered, "expired", attempt.error(), clock.instant());
            case REJECTED -> dlq.put(DeadLetters.NOTIFICATION, key, branchId, body, (int) delivered, "rejected", attempt.error(), clock.instant());
            case UNAVAILABLE, CIRCUIT_OPEN -> {
                if (delivered < props.maxDeliver()) {
                    msg.nakWithDelay(attempt.result() == NotificationSender.Result.CIRCUIT_OPEN
                            ? props.circuitBreaker().waitInOpenState() : delayFor(delivered));
                    return;
                }
                dlq.put(DeadLetters.NOTIFICATION, key, branchId, body, (int) delivered, "exhausted", attempt.error(), clock.instant());
            }
        }
        msg.ack();
    }
}
