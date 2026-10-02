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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Heartbeat {@code branch.*.hb} → registry → source in {@code CENTER_EVT} and the first config version.
 * A new branch only needs the center address and the leaf password; nothing is set up by hand at the center.
 * Periodic reconciliation picks up branches whose source could not be attached on the first attempt.
 *
 * <p>Only a branch the center has never seen is written right away; heartbeats of known branches go to the
 * {@link HeartbeatBuffer} and are written in batches by {@link HeartbeatFlush} (design 6.2). After a restart the set of
 * known branches is empty and fills up from the database on the first heartbeat of each.
 */
@Component
public class HeartbeatListener {
    private static final Logger log = LoggerFactory.getLogger(HeartbeatListener.class);

    private final CenterNats nats;
    private final CenterStreams streams;
    private final BranchRegistry registry;
    private final ConfigPublisher config;
    private final HeartbeatBuffer buffer;
    private final JsonMapper json;
    private final Clock clock;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<String> known = ConcurrentHashMap.newKeySet();
    private volatile Dispatcher dispatcher;

    public HeartbeatListener(CenterNats nats, CenterStreams streams, BranchRegistry registry, ConfigPublisher config,
                             HeartbeatBuffer buffer, JsonMapper json, Clock clock) {
        this.nats = nats; this.streams = streams; this.registry = registry; this.config = config;
        this.buffer = buffer; this.json = json; this.clock = clock;
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
            var receivedAt = clock.instant();
            var body = new String(msg.getData(), StandardCharsets.UTF_8);
            JsonNode hb = json.readTree(body);
            var name = hb.path("name").asString(branchId);
            var timezone = hb.path("timezone").asString("Europe/Moscow");
            if (known.contains(branchId) || registry.exists(branchId)) {
                known.add(branchId);
                buffer.put(new Heartbeat(branchId, name, timezone, body, receivedAt));
                return;
            }
            boolean isNew = registry.upsertHeartbeat(branchId, name, timezone, body, receivedAt);
            known.add(branchId);
            if (isNew) {
                log.info("new branch registered id={} name={}", branchId, hb.path("name").asString(""));
                attach(branchId);
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
            registry.withoutSource().forEach(this::attach);
        } catch (RuntimeException e) {
            log.warn("reconcile failed: {}", e.toString());
        }
    }

    private void attach(String branchId) {
        if (streams.attachBranch(branchId)) {
            registry.markSourceAttached(branchId);
        }
    }
}
