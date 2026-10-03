package honey.road.node.messaging.heartbeat;

import honey.road.contracts.event.Subjects;
import honey.road.contracts.http.EsuoHeaders;
import honey.road.node.demo.DemoClock;
import honey.road.node.config.JsonCodec;
import honey.road.node.config.NodeProperties;
import honey.road.node.messaging.nats.NatsGateway;
import honey.road.node.messaging.outbox.OutboxWorker;
import honey.road.node.monitoring.Lifecycle;
import honey.road.node.monitoring.SysMetrics;
import honey.road.node.persistence.CatalogRepository;
import honey.road.node.planning.LoadPlanner;
import honey.road.node.persistence.PolicyRepository;
import honey.road.node.persistence.TicketRepository;
import honey.road.node.persistence.WindowRepository;
import io.nats.client.impl.Headers;
import jakarta.annotation.PreDestroy;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Heartbeat {@code branch.{b}.hb} (core NATS, sect 10.1); also registers the branch in the center: the first heartbeat
 * of an unknown branch adds it to the registry and as a {@code CENTER_EVT} source.
 * Carries the service catalog for the public branch picker.
 *
 * <p>The queue part reads the database, the {@code sys} block does not. When the database does not answer within
 * {@link #DB_TIMEOUT} (a lock holding every pooled connection, a frozen server), the heartbeat still goes out with the
 * last known queue numbers, a fresh {@code sys} block and {@code db_ok=false}: otherwise the center would show the branch
 * as offline while only its database is stuck. A read still hanging is not started again.
 */
@Component
public class HeartbeatPublisher {
    private static final Logger log = LoggerFactory.getLogger(HeartbeatPublisher.class);
    static final Duration DB_TIMEOUT = Duration.ofSeconds(3);

    private final NatsGateway nats;
    private final NodeProperties node;
    private final CatalogRepository catalog;
    private final WindowRepository windows;
    private final TicketRepository tickets;
    private final PolicyRepository policies;
    private final LoadPlanner planner;
    private final OutboxWorker outbox;
    private final SysMetrics sys;
    private final JsonCodec json;
    private final Clock clock;
    private @Nullable Boolean dbStuck;                           // null until the first heartbeat
    private final ExecutorService reads = Executors.newVirtualThreadPerTaskExecutor();
    private @Nullable Future<Map<String, Object>> reading;
    private @Nullable Map<String, Object> lastQueue;
    private long lastQueueNanos;

    public HeartbeatPublisher(NatsGateway nats, NodeProperties node, CatalogRepository catalog, WindowRepository windows,
                              TicketRepository tickets, PolicyRepository policies, LoadPlanner planner,
                              OutboxWorker outbox, SysMetrics sys, JsonCodec json, Clock clock) {
        this.nats = nats; this.node = node; this.catalog = catalog; this.windows = windows; this.tickets = tickets;
        this.policies = policies; this.planner = planner; this.outbox = outbox; this.sys = sys; this.json = json;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${esuo.nats.heartbeat-interval:10s}", initialDelayString = "2s")
    public void beat() {
        var connection = nats.connection();
        if (connection.isEmpty()) {
            return;
        }
        try {
            var hb = current();
            if (hb == null) {
                return;
            }
            var body = json.writeValueAsString(hb);
            connection.get().publish(Subjects.heartbeat(node.branchId()),
                    new Headers().add(EsuoHeaders.CONTENT_TYPE, "application/json"),
                    body.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            log.warn("heartbeat failed: {}", e.toString());
        }
    }

    /** What goes out now; {@code null} when the database has not answered once since the start. */
    public synchronized @Nullable Map<String, Object> current() {
        if (reading == null || reading.isDone()) {
            reading = reads.submit(this::snapshot);
        }
        Map<String, Object> hb;
        try {
            hb = new LinkedHashMap<>(reading.get(DB_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            lastQueue = hb;
            lastQueueNanos = System.nanoTime();
            if (Boolean.TRUE.equals(dbStuck)) {
                Lifecycle.info(log, "db.answering", "database answers again, heartbeat carries fresh queue numbers");
            }
            dbStuck = false;
        } catch (TimeoutException | ExecutionException e) {
            if (e instanceof ExecutionException) {
                reading = null;
            }
            if (lastQueue == null) {
                log.warn("heartbeat skipped: the database has not answered yet ({})", e.toString());
                return null;
            }
            if (!Boolean.TRUE.equals(dbStuck)) {
                Lifecycle.warn(log, "db.stuck", "database does not answer within {} ms, heartbeat carries the last known queue numbers: {}",
                        DB_TIMEOUT.toMillis(), e instanceof ExecutionException ee && ee.getCause() != null ? ee.getCause().toString() : "timeout");
            }
            dbStuck = true;
            hb = new LinkedHashMap<>(lastQueue);
            hb.put("sent_at", clock.instant());
            hb.put("db_ok", false);
            hb.put("db_unavailable_sec", Duration.ofNanos(System.nanoTime() - lastQueueNanos).toSeconds());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        putSys(hb);
        return hb;
    }

    @PreDestroy
    void stopReads() {
        reads.shutdownNow();
    }

    /** The database part of a heartbeat: queue, windows, config versions, load, catalog. */
    public Map<String, Object> snapshot() {
        var branch = catalog.branch(node.branchId());
        var stats = outbox.stats();
        var allWindows = windows.all(node.branchId());
        var waiting = tickets.waiting(node.branchId());
        var now = clock.instant();
        var hb = new LinkedHashMap<String, Object>();
        hb.put("branch_id", branch.id());
        hb.put("name", branch.name());
        hb.put("timezone", branch.timezone());
        hb.put("sent_at", now);
        if (clock instanceof DemoClock demo) {
            hb.put("demo_rate", demo.rate());                  // the center converts TTLs to branch time (sect 11.3)
        }
        // config_version: the version published by the center (lets the center see whether it was applied);
        // effective_config_version also grows with local admin overrides and is stamped into tickets.
        var versions = policies.versions();
        hb.put("config_version", versions.central());
        hb.put("effective_config_version", versions.effective());
        hb.put("outbox_pending", stats.pending());
        hb.put("outbox_lag_sec", stats.oldestAge().toSeconds());
        hb.put("windows_total", allWindows.size());
        // open only: a closing window accepts no new clients.
        hb.put("windows_open", allWindows.stream().filter(w -> "open".equals(w.status())).count());
        hb.put("waiting", waiting.size());
        hb.put("overdue", waiting.stream().filter(t -> !t.dueAt().isAfter(now)).count());
        hb.put("oldest_wait_sec", waiting.stream().map(t -> Duration.between(t.issuedAt(), now).toSeconds())
                .max(Long::compare).orElse(0L));
        putLoad(hb);
        hb.put("services", catalog.services(node.branchId()).stream().filter(s -> s.active())
                .map(s -> Map.of("id", s.id(), "code", s.code(), "name", s.name(), "number_prefix", s.numberPrefix()))
                .toList());
        return hb;
    }

    /** The server's own state (design 6.1). Like the load fields, it must never cost the center its heartbeat. */
    private void putSys(Map<String, Object> hb) {
        try {
            hb.put("sys", sys.sample());
        } catch (RuntimeException e) {
            log.debug("heartbeat without sys block: {}", e.toString());
        }
    }

    /**
     * Load for the network overview (load-planning.md 6.2).
     *
     * <p>{@code load_level_since} travels with the level so the center can raise the {@code overload} anomaly
     * for a branch stuck there longer than fifteen minutes without keeping a history of levels of its own. The
     * branch already knows that moment exactly — it survives a restart in {@code load_state} — and a derived
     * value is better sent than recomputed from a stream of heartbeats that a link outage can interrupt.
     *
     * <p>Planning is an addition to the queue, so its failure must not cost the center its heartbeat: without
     * these fields the overview shows what it showed before planning existed.
     */
    private void putLoad(Map<String, Object> hb) {
        try {
            var load = planner.snapshot();
            hb.put("load_level", load.level());
            hb.put("load_level_since", load.levelSince());
            hb.put("clear_time_min", load.clearTimeMin());
            if (load.profile() != null) {
                hb.put("day_profile", load.profile().profile().code());
            }
        } catch (RuntimeException e) {
            log.debug("heartbeat without load fields: {}", e.toString());
        }
    }
}
