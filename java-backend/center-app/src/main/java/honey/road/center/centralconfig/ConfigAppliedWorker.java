package honey.road.center.centralconfig;

import honey.road.center.nats.CenterNats;
import honey.road.center.nats.CenterStreams;
import honey.road.center.nats.DurableWorker;
import honey.road.contracts.event.EventTypes;
import honey.road.contracts.event.Subjects;
import io.nats.client.Message;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** {@code config-applied} consumer (sect 10.5): records {@code config_applied_version} from {@code config.applied}. */
@Component
class ConfigAppliedWorker extends DurableWorker {
    private final ConfigStore store;
    private final JsonMapper json;
    private final Clock clock;

    ConfigAppliedWorker(CenterNats nats, CenterStreams streams, ConfigStore store, JsonMapper json, Clock clock) {
        super(nats, streams, "config-applied", "branch.*.evt." + EventTypes.CONFIG_APPLIED);
        this.store = store; this.json = json; this.clock = clock;
    }

    @Override
    protected ConsumerConfiguration.Builder configure(ConsumerConfiguration.Builder b) {
        return b.deliverPolicy(DeliverPolicy.All).ackWait(Duration.ofSeconds(30)).maxDeliver(-1);
    }

    @Override
    protected void handle(Message msg) {
        var event = json.readTree(msg.getData());
        var branchId = event.path("branch_id").asString(Subjects.branchIdOf(msg.getSubject()));
        int version = event.path("data").path("central_version").asInt(0);
        if (branchId != null && version > 0) {
            var at = event.hasNonNull("occurred_at") ? Instant.parse(event.path("occurred_at").asString()) : clock.instant();
            store.markApplied(branchId, version, at);
        }
        msg.ack();
    }
}
