package honey.road.node.messaging.nats;

import honey.road.node.messaging.outbox.OutboxWorker;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * {@code nats} health component. Always UP: the node serves clients without the broker (sect 14);
 * degradation shows in details. Not part of readiness.
 */
@Component("nats")
public class NatsHealthIndicator implements HealthIndicator {
    private static final Duration LAG_WARN = Duration.ofSeconds(60);

    private final NatsGateway nats;
    private final BranchStreams streams;
    private final OutboxWorker outbox;

    public NatsHealthIndicator(NatsGateway nats, BranchStreams streams, OutboxWorker outbox) {
        this.nats = nats;
        this.streams = streams;
        this.outbox = outbox;
    }

    @Override
    public Health health() {
        var stats = outbox.stats();
        var builder = Health.up()
                .withDetail("connected", nats.isConnected())
                .withDetail("state", nats.state())
                .withDetail("state_since", nats.stateSince().toString())
                .withDetail("url", nats.url())
                .withDetail("branch_evt_stream", streams.branchEvtReady())
                .withDetail("outbox_pending", stats.pending())
                .withDetail("outbox_oldest_age_sec", stats.oldestAge().toSeconds());
        if (!nats.isConnected()) {
            builder.withDetail("mode", "autonomous");
        }
        if (stats.oldestAge().compareTo(LAG_WARN) > 0) {
            builder.withDetail("warning", "outbox lag > 60s");
        }
        return builder.build();
    }
}
