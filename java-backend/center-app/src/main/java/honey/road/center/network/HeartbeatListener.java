package honey.road.center.network;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import honey.road.center.nats.CenterNats;
import honey.road.center.nats.CenterStreams;
import honey.road.contracts.event.Subjects;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/**
 * Heartbeat {@code branch.*.hb} → registry → source in {@code CENTER_EVT} and the first config version.
 * A new branch only needs the center address and the leaf password; nothing is set up by hand at the center.
 * Periodic reconciliation picks up branches whose source could not be attached on the first attempt.
 *
 * <p>Heartbeats of known branches go to the {@link HeartbeatBuffer} and are written in batches by
 * {@link HeartbeatFlush} (design 6.2). The first heartbeat of a branch this process does not know yet — new, or known
 * from before a restart — goes to {@link BranchOnboarding}, which checks the database and registers in batches. The
 * NATS dispatcher itself never waits on the database: a wave of 40 000 new branches (final plan, C1) used to put one
 * upsert, one source update and one config publish per heartbeat on unbounded virtual threads, and the center fell
 * behind, was cut off by the hub as a slow consumer and ran out of memory.
 *
 * <p>A heartbeat with {@code events_via} (a branch of a regional leaf, design doc 3.8) is sourced through its
 * region's {@code REGION_EVT} instead of a {@code BRANCH_EVT} of its own.
 */
@Component
public class HeartbeatListener {
    private static final Logger log = LoggerFactory.getLogger(HeartbeatListener.class);

    private final CenterNats nats;
    private final CenterStreams streams;
    private final BranchRegistry registry;
    private final HeartbeatBuffer buffer;
    private final BranchOnboarding onboarding;
    private final JsonMapper json;
    private final Clock clock;
    private volatile Dispatcher dispatcher;

    private final Counter received;

    public HeartbeatListener(CenterNats nats, CenterStreams streams, BranchRegistry registry, HeartbeatBuffer buffer,
                             BranchOnboarding onboarding, JsonMapper json, Clock clock, MeterRegistry meters) {
        this.nats = nats; this.streams = streams; this.registry = registry; this.buffer = buffer;
        this.onboarding = onboarding; this.json = json; this.clock = clock;
        this.received = Counter.builder("esuo.heartbeats.received").description("Heartbeats received from branches")
                .register(meters);
    }

    @PostConstruct
    void register() {
        nats.onConnected(this::subscribe);
    }

    synchronized void subscribe(Connection c) {
        if (dispatcher != null && dispatcher.isActive()) { return; }
        dispatcher = c.createDispatcher(this::handle);          // parse and a map put: nothing to offload
        dispatcher.subscribe(Subjects.ALL_HEARTBEATS);
        log.info("listening heartbeats on {}", Subjects.ALL_HEARTBEATS);
    }

    void handle(Message msg) {
        var branchId = Subjects.branchIdOf(msg.getSubject());
        if (branchId == null || !branchId.matches("[a-zA-Z0-9_-]{1,64}")) { return; }
        try {
            received.increment();
            var receivedAt = clock.instant();
            JsonNode hb = json.readTree(msg.getData());
            var eventsVia = EventsVia.of(hb.path("events_via").asString(null));
            var heartbeat = new Heartbeat(branchId, hb.path("name").asString(branchId),
                    hb.path("timezone").asString("Europe/Moscow"), msg.getData(), receivedAt, eventsVia != null);
            if (onboarding.known(branchId)) {
                buffer.put(heartbeat);
            } else {
                onboarding.offer(heartbeat, eventsVia);
            }
        } catch (RuntimeException e) {
            log.warn("bad heartbeat from {}: {}", branchId, e.toString());
        }
    }

    /** Reconciliation: branches without a source (center restarted, NATS unavailable, etc.). */
    @Scheduled(fixedDelayString = "PT15S", initialDelayString = "PT5S")
    public void reconcile() {
        if (!streams.ready()) { return; }
        try {
            for (var b : registry.withoutSource()) {
                if (onboarding.pending(b.id())) { continue; }        // the onboarding worker gets to it first
                if (b.eventsVia() == null ? streams.attachBranch(b.id()) : streams.attachRegion(b.eventsVia())) {
                    registry.markSourceAttached(b.id());
                }
            }
        } catch (RuntimeException e) {
            log.warn("reconcile failed: {}", e.toString());
        }
    }
}
