package honey.road.center.network;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Counter;
import honey.road.center.centralconfig.ConfigPublisher;
import honey.road.center.nats.CenterStreams;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * First heartbeat of a branch this process does not know yet, off the NATS thread (final plan, C1/C3b). One worker
 * takes them in batches of up to {@value #BATCH}:
 * <ul>
 *   <li>branches already in the registry (a center restart) only become known — their heartbeat goes to the
 *   {@link HeartbeatBuffer} like any other, nothing is registered or published again;</li>
 *   <li>new branches are inserted with one multi-row statement, their events are sourced into {@code CENTER_EVT}
 *   (own {@code BRANCH_EVT}, or the region's {@code REGION_EVT} once per region) and they get their first config.</li>
 * </ul>
 * 40 000 branches appearing at once queue up here instead of piling on the database and NATS. {@link #pending} lets
 * the config reconciler and the source reconciliation leave queued branches alone: the reconciler used to publish a
 * second version to every branch registration had not reached yet.
 */
@Component
public class BranchOnboarding {
    private static final Logger log = LoggerFactory.getLogger(BranchOnboarding.class);
    static final int BATCH = 1000;
    static final int LOG_EACH_UP_TO = 20;

    record First(Heartbeat heartbeat, @Nullable String eventsVia) {
    }

    private final CenterStreams streams;
    private final BranchRegistry registry;
    private final ConfigPublisher config;
    private final HeartbeatBuffer buffer;
    /** Latest first heartbeat per branch waiting for the worker; the queue holds each id once. */
    private final Map<String, First> pending = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final Set<String> known = ConcurrentHashMap.newKeySet();
    private final Object idle = new Object();
    private volatile Thread worker;

    private final Counter registeredCount;

    BranchOnboarding(CenterStreams streams, BranchRegistry registry, ConfigPublisher config, HeartbeatBuffer buffer,
                     MeterRegistry meters) {
        this.streams = streams; this.registry = registry; this.config = config; this.buffer = buffer;
        this.registeredCount = Counter.builder("esuo.onboarding.registered").description("New branches registered")
                .register(meters);
        Gauge.builder("esuo.onboarding.queue", queue, LinkedBlockingQueue::size)
                .description("New branches waiting for registration").register(meters);
    }

    @PostConstruct
    void start() {
        worker = Thread.ofPlatform().name("branch-onboarding").daemon().start(this::run);
    }

    @PreDestroy
    void stop() {
        var w = worker;
        if (w != null) { w.interrupt(); }
    }

    /** The branch is known to this process: its heartbeats go straight to the buffer. */
    boolean known(String id) {
        return known.contains(id);
    }

    void offer(Heartbeat heartbeat, @Nullable String eventsVia) {
        if (pending.put(heartbeat.branchId(), new First(heartbeat, eventsVia)) == null) {
            queue.add(heartbeat.branchId());
        }
    }

    /** New branches waiting for the worker. */
    public int queued() {
        return queue.size();
    }

    /** The branch waits for the worker: not yet registered, sourced or configured by it. */
    public boolean pending(String id) {
        return pending.containsKey(id);
    }

    public int backlog() {
        return pending.size();
    }

    /** For tests and shutdown: waits until every offered heartbeat has been handled. */
    boolean awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (idle) {
            while (!pending.isEmpty()) {
                long left = (deadline - System.nanoTime()) / 1_000_000;
                if (left <= 0) { return false; }
                try {
                    idle.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    private void run() {
        var ids = new ArrayList<String>(BATCH);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                ids.add(queue.take());
                queue.drainTo(ids, BATCH - 1);
                var batch = new ArrayList<First>(ids.size());
                ids.forEach(id -> { var f = pending.get(id); if (f != null) { batch.add(f); } });
                onboard(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.warn("onboarding batch of {} failed, next heartbeats retry it: {}", ids.size(), e.toString());
            } finally {
                ids.forEach(pending::remove);
                ids.clear();
                synchronized (idle) { idle.notifyAll(); }
            }
        }
    }

    void onboard(List<First> batch) {
        var ids = batch.stream().map(f -> f.heartbeat().branchId()).toList();
        var existing = new HashSet<>(registry.existing(ids));
        var fresh = new ArrayList<First>();
        for (var f : batch) {
            if (existing.contains(f.heartbeat().branchId())) {
                buffer.put(f.heartbeat());                   // known from before a restart: just the next flush
            } else {
                fresh.add(f);
            }
        }
        var inserted = new HashSet<>(registry.register(fresh.stream().map(First::heartbeat).toList()));
        for (var f : fresh) {
            if (!inserted.contains(f.heartbeat().branchId())) {
                buffer.put(f.heartbeat());                   // another center replica registered it meanwhile
            }
        }
        known.addAll(ids);
        var registered = fresh.stream().filter(f -> inserted.contains(f.heartbeat().branchId())).toList();
        if (registered.isEmpty()) { return; }
        registeredCount.increment(registered.size());

        log(registered);
        var attached = new ArrayList<String>();
        for (var f : registered) {
            var id = f.heartbeat().branchId();
            if (f.eventsVia() == null ? streams.attachBranch(id) : streams.attachRegion(f.eventsVia())) {
                attached.add(id);
            }
        }
        registry.markSourceAttached(attached);           // the rest are retried by HeartbeatListener.reconcile
        for (var f : registered) {
            try {
                config.publishTo(f.heartbeat().branchId(), "system");   // a new branch gets its users and rules right away
            } catch (RuntimeException e) {
                log.warn("first config for {} not published (reconciler retries): {}", f.heartbeat().branchId(), e.toString());
            }
        }
    }

    private static void log(List<First> registered) {
        if (registered.size() <= LOG_EACH_UP_TO) {
            registered.forEach(f -> log.info("new branch registered id={} name={}{}", f.heartbeat().branchId(),
                    f.heartbeat().name(), f.eventsVia() == null ? "" : " events_via=" + f.eventsVia()));
            return;
        }
        var byVia = new TreeMap<String, Integer>();
        registered.forEach(f -> byVia.merge(f.eventsVia() == null ? "own leaf" : f.eventsVia(), 1, Integer::sum));
        log.info("{} new branches registered: {}", registered.size(), byVia);
    }
}
