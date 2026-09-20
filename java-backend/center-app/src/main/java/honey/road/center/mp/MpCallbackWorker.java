package honey.road.center.mp;

import honey.road.center.config.CenterProperties;
import honey.road.center.dlq.DeadLetters;
import honey.road.center.nats.CenterNats;
import honey.road.center.nats.CenterStreams;
import honey.road.center.nats.DurableWorker;
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
 * {@code mp-callback} (sect 10.5): {@code branch.*.evt.mp.appointment.>}, deliver_policy all, max_deliver 10,
 * backoff 1, 2, 5, 15, 30, 60 s. 4xx → DLQ at once; 5xx, timeout, unavailable branch → retry, then DLQ.
 * Events for unknown {@code mp_appointment_id} are acked without action.
 */
@Component
class MpCallbackWorker extends DurableWorker {
    private final MpCallbackService callbacks;
    private final DeadLetters dlq;
    private final CenterProperties.Mp props;
    private final JsonMapper json;
    private final Clock clock;

    MpCallbackWorker(CenterNats nats, CenterStreams streams, MpCallbackService callbacks, DeadLetters dlq,
                     CenterProperties props, JsonMapper json, Clock clock) {
        super(nats, streams, "mp-callback", "branch.*.evt.mp.appointment.>");
        this.callbacks = callbacks; this.dlq = dlq; this.props = props.mp(); this.json = json; this.clock = clock;
    }

    @Override
    protected ConsumerConfiguration.Builder configure(ConsumerConfiguration.Builder b) {
        return b.deliverPolicy(DeliverPolicy.All).ackWait(Duration.ofSeconds(30)).maxDeliver(props.maxDeliver());
    }

    @Override
    protected List<Duration> backoff() { return props.backoff(); }

    @Override
    protected void handle(Message msg) {
        var body = new String(msg.getData(), StandardCharsets.UTF_8);
        var event = json.readTree(body);
        var id = MpCallbackService.appointmentId(event);
        var branchId = event.path("branch_id").asString("?");
        long delivered = deliveredCount(msg);
        var attempt = callbacks.deliver(event);
        switch (attempt.result()) {
            case DELIVERED -> dlq.resolveKey(DeadLetters.MP_CALLBACK, id, clock.instant());
            case UNKNOWN -> { }
            case REJECTED -> dlq.put(DeadLetters.MP_CALLBACK, id, branchId, body, (int) delivered, "rejected", attempt.error(), clock.instant());
            case UNAVAILABLE -> {
                if (delivered < props.maxDeliver()) {
                    retryLater(msg);
                    return;
                }
                callbacks.exhausted(id);
                dlq.put(DeadLetters.MP_CALLBACK, id, branchId, body, (int) delivered, "exhausted", attempt.error(), clock.instant());
            }
        }
        msg.ack();
    }
}
