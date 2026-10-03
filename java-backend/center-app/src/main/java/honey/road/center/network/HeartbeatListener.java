package honey.road.center.network;

import honey.road.center.config.CenterProperties;
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
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Heartbeat {@code branch.*.hb} → registry → source in {@code CENTER_EVT} and the first config version. A heartbeat
 * with {@code events_via} (branch of a regional leaf, design doc 3.8) brings in that region's stream instead.
 * A new branch only needs the center address and the leaf password; nothing is set up by hand at the center.
 *
 * <p>Batched (final plan, C3b): receiving only puts the heartbeat into {@link HeartbeatBuffer}; every
 * {@code flush-interval} the latest heartbeat of each branch is upserted in multi-row statements and sampled into
 * {@code branch_metrics}. Before, every heartbeat was its own upsert on its own virtual thread: at 4 000 heartbeats a
 * second the center fell behind, the hub cut it off as a slow consumer and the unbounded backlog ran it out of memory.
 * New branches go to {@link BranchOnboarding}. Periodic reconciliation picks up branches whose source could not be
 * attached on the first attempt.
 */
@Component
public class HeartbeatListener {
    private static final Logger log = LoggerFactory.getLogger(HeartbeatListener.class);

    private final CenterNats nats;
    private final CenterStreams streams;
    private final BranchRegistry registry;
    private final BranchOnboarding onboarding;
    private final CenterProperties.Heartbeat props;
    private final JsonMapper json;
    private final Clock clock;
    private final HeartbeatBuffer buffer;
    /** When each branch last got a {@code branch_metrics} row. */
    private final Map<String, Instant> lastMetrics = new ConcurrentHashMap<>();
    private volatile Dispatcher dispatcher;

    public HeartbeatListener(CenterNats nats, CenterStreams streams, BranchRegistry registry, BranchOnboarding onboarding,
                             CenterProperties props, JsonMapper json, Clock clock) {
        this.nats = nats; this.streams = streams; this.registry = registry; this.onboarding = onboarding;
        this.props = props.heartbeat(); this.json = json; this.clock = clock;
        this.buffer = new HeartbeatBuffer(this.props.maxBranches());
    }

    @PostConstruct
    void register() {
        nats.onConnected(this::subscribe);
    }

    synchronized void subscribe(Connection c) {
        if (dispatcher != null && dispatcher.isActive()) { return; }
        dispatcher = c.createDispatcher(this::handle);              // a map put: nothing to offload to other threads
        dispatcher.subscribe(Subjects.ALL_HEARTBEATS);
        log.info("listening heartbeats on {}", Subjects.ALL_HEARTBEATS);
    }

    void handle(Message msg) {
        var branchId = Subjects.branchIdOf(msg.getSubject());
        if (branchId == null || !branchId.matches("[a-zA-Z0-9_-]{1,64}")) { return; }
        buffer.offer(branchId, msg.getData(), clock.instant());
    }

    @Scheduled(fixedDelayString = "${esuo.center.heartbeat.flush-interval:PT5S}", initialDelayString = "PT2S")
    public void flush() {
        var received = buffer.drain();
        if (received.isEmpty()) { return; }
        long started = System.nanoTime();
        var rows = new ArrayList<HeartbeatRow>(received.size());
        for (var r : received) {
            try {
                rows.add(HeartbeatRow.parse(json, r));
            } catch (RuntimeException e) {
                log.warn("bad heartbeat from {}: {}", r.branchId(), e.toString());
            }
        }
        try {
            var inserted = registry.upsertHeartbeats(rows);
            var sampled = rows.stream().filter(r -> {
                var last = lastMetrics.get(r.branchId());
                return last == null || !r.receivedAt().isBefore(last.plus(props.metricsEvery()));
            }).toList();
            registry.insertMetrics(sampled);
            sampled.forEach(r -> lastMetrics.put(r.branchId(), r.receivedAt()));
            if (!inserted.isEmpty()) {
                var via = new java.util.HashMap<String, String>();
                rows.forEach(r -> via.put(r.branchId(), r.eventsVia()));
                inserted.forEach(id -> onboarding.enqueue(id, via.get(id)));
            }
            long ms = (System.nanoTime() - started) / 1_000_000;
            if (ms > props.flushInterval().toMillis() / 2) {
                log.warn("heartbeat flush of {} branches took {} ms (new {}, metrics {}, dropped so far {})",
                        rows.size(), ms, inserted.size(), sampled.size(), buffer.dropped());
            } else {
                log.debug("heartbeat flush: {} branches in {} ms, new {}, metrics {}", rows.size(), ms, inserted.size(), sampled.size());
            }
        } catch (RuntimeException e) {
            // The next heartbeat of every branch arrives within its interval: losing one flush loses nothing lasting.
            log.warn("heartbeat flush of {} branches failed: {}", rows.size(), e.toString());
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

    /** Raw metrics older than the retention go away; hourly is plenty for a 48 h window. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    public void purgeMetrics() {
        try {
            int n = registry.deleteMetricsBefore(clock.instant().minus(props.metricsRetention()));
            if (n > 0) { log.info("purged {} branch_metrics rows older than {}", n, props.metricsRetention()); }
        } catch (RuntimeException e) {
            log.warn("branch_metrics purge failed: {}", e.toString());
        }
    }

    int buffered() {
        return buffer.size();
    }
}
