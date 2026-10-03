package honey.road.center.network;

import honey.road.center.centralconfig.ConfigPublisher;
import honey.road.center.nats.CenterStreams;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Everything a newly seen branch needs, off the heartbeat path: its events sourced into {@code CENTER_EVT} (own
 * {@code BRANCH_EVT} or the region's {@code REGION_EVT}) and its first config. One worker thread, so 40 000 branches
 * appearing in one flush queue up here instead of piling on the database and NATS at once.
 *
 * <p>{@link #pending} lets the config reconciler leave queued branches alone: it would otherwise publish a second
 * version to every branch the worker has not reached yet.
 */
@Component
public class BranchOnboarding {
    private static final Logger log = LoggerFactory.getLogger(BranchOnboarding.class);
    static final int BATCH = 1000;
    static final int LOG_EACH_UP_TO = 20;

    record NewBranch(String id, @Nullable String eventsVia) {
    }

    private final CenterStreams streams;
    private final BranchRegistry registry;
    private final ConfigPublisher config;
    private final LinkedBlockingQueue<NewBranch> queue = new LinkedBlockingQueue<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private volatile Thread worker;

    BranchOnboarding(CenterStreams streams, BranchRegistry registry, ConfigPublisher config) {
        this.streams = streams; this.registry = registry; this.config = config;
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

    void enqueue(String id, @Nullable String eventsVia) {
        if (pending.add(id)) {
            queue.add(new NewBranch(id, eventsVia));
        }
    }

    /** The branch is registered but not yet sourced and configured by this worker. */
    public boolean pending(String id) {
        return pending.contains(id);
    }

    public int backlog() {
        return queue.size();
    }

    private void run() {
        var batch = new ArrayList<NewBranch>(BATCH);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                batch.add(queue.take());
                queue.drainTo(batch, BATCH - 1);
                onboard(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.warn("onboarding batch failed: {}", e.toString());
            } finally {
                batch.forEach(b -> pending.remove(b.id()));
                batch.clear();
            }
        }
    }

    void onboard(List<NewBranch> batch) {
        var byVia = new TreeMap<String, Integer>();
        batch.forEach(b -> byVia.merge(b.eventsVia() == null ? "own leaf" : b.eventsVia(), 1, Integer::sum));
        if (batch.size() <= LOG_EACH_UP_TO) {
            batch.forEach(b -> log.info("new branch registered id={}{}", b.id(), b.eventsVia() == null ? "" : " events_via=" + b.eventsVia()));
        } else {
            log.info("{} new branches registered: {}", batch.size(), Map.copyOf(byVia));
        }
        var attached = new ArrayList<String>();
        for (var b : batch) {
            if (b.eventsVia() == null ? streams.attachBranch(b.id()) : streams.attachRegion(b.eventsVia())) {
                attached.add(b.id());
            }
        }
        registry.markSourceAttached(attached);       // the rest are retried by HeartbeatListener.reconcile
        for (var b : batch) {
            try {
                config.publishTo(b.id(), "system");  // a new branch gets its users and rules right away
            } catch (RuntimeException e) {
                log.warn("first config for {} not published (reconciler retries): {}", b.id(), e.toString());
            }
        }
    }
}
