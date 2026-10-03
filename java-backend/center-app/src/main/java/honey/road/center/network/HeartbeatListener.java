package honey.road.center.network;

import honey.road.center.centralconfig.ConfigPublisher;
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

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Heartbeat {@code branch.*.hb} → registry → source in {@code CENTER_EVT} and the first config version. A heartbeat
 * with {@code events_via} (branch of a regional leaf, design doc 3.8) brings in that region's stream instead.
 * A new branch only needs the center address and the leaf password; nothing is set up by hand at the center.
 * Periodic reconciliation picks up branches whose source could not be attached on the first attempt.
 */
@Component
public class HeartbeatListener {
    private static final Logger log = LoggerFactory.getLogger(HeartbeatListener.class);

    private final CenterNats nats;
    private final CenterStreams streams;
    private final BranchRegistry registry;
    private final ConfigPublisher config;
    private final JsonMapper json;
    private final Clock clock;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Dispatcher dispatcher;

    public HeartbeatListener(CenterNats nats, CenterStreams streams, BranchRegistry registry, ConfigPublisher config,
                             JsonMapper json, Clock clock) {
        this.nats = nats; this.streams = streams; this.registry = registry; this.config = config;
        this.json = json; this.clock = clock;
    }

    @PostConstruct
    void register() {
        nats.onConnected(this::subscribe);
    }

    synchronized void subscribe(Connection c) {
        if (dispatcher != null && dispatcher.isActive()) { return; }
        dispatcher = c.createDispatcher(msg -> workers.execute(() -> handle(msg)));
        dispatcher.subscribe(Subjects.ALL_HEARTBEATS);
        log.info("listening heartbeats on {}", Subjects.ALL_HEARTBEATS);
    }

    void handle(Message msg) {
        var branchId = Subjects.branchIdOf(msg.getSubject());
        if (branchId == null || !branchId.matches("[a-zA-Z0-9_-]{1,64}")) { return; }
        try {
            var body = new String(msg.getData(), StandardCharsets.UTF_8);
            JsonNode hb = json.readTree(body);
            boolean isNew = registry.upsertHeartbeat(branchId, hb.path("name").asString(branchId),
                    hb.path("timezone").asString("Europe/Moscow"), body, clock.instant());
            if (isNew) {
                var via = EventsVia.of(hb.path("events_via").asString(null));
                log.info("new branch registered id={} name={}{}", branchId, hb.path("name").asString(""),
                        via == null ? "" : " events_via=" + via);
                attach(branchId, via);
                config.publishTo(branchId, "system");         // new branch gets its users and rules right away
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
            registry.withoutSource().forEach(b -> attach(b.id(), b.eventsVia()));
        } catch (RuntimeException e) {
            log.warn("reconcile failed: {}", e.toString());
        }
    }

    /** Own {@code BRANCH_EVT} of the branch, or the {@code REGION_EVT} of its region when the heartbeat names one. */
    private void attach(String branchId, String eventsVia) {
        if (eventsVia == null ? streams.attachBranch(branchId) : streams.attachRegion(eventsVia)) {
            registry.markSourceAttached(branchId);
        }
    }
}
