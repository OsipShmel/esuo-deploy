package honey.road.center.network;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Counter;
import honey.road.center.config.CenterProperties;
import honey.road.center.monitoring.BranchMetrics;
import honey.road.center.monitoring.MetricsRow;
import jakarta.annotation.PreDestroy;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writes buffered heartbeats every few seconds (design 6.2): first "seen" for every branch of the batch, then per
 * chunk one transaction upserts {@code branches} and appends {@code branch_metrics}, instead of a transaction per
 * heartbeat. A failed write puts the chunk back unless a fresher heartbeat replaced it. The
 * metrics go under a savepoint: whatever breaks them, the branches stay online and only that batch of metric rows is
 * lost. Branches behind a regional hub get their own, coarser resolution (scale design 5.1).
 */
@Component
public class HeartbeatFlush {
    private static final Logger log = LoggerFactory.getLogger(HeartbeatFlush.class);

    private final HeartbeatBuffer buffer;
    private final BranchRegistry registry;
    private final BranchMetrics metrics;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final int chunk;
    private final Duration resolution;
    private final Duration resolutionRegional;
    private final Cadence cadence = new Cadence();

    private final Timer flushTimer;
    private final DistributionSummary flushed;
    private final Counter rowsWritten;
    private final Counter rowsLost;

    public HeartbeatFlush(HeartbeatBuffer buffer, BranchRegistry registry, BranchMetrics metrics,
                          PlatformTransactionManager transactions, JsonMapper json, CenterProperties props,
                          MeterRegistry meters) {
        this.buffer = buffer; this.registry = registry; this.metrics = metrics;
        this.tx = new TransactionTemplate(transactions); this.json = json;
        this.chunk = props.metrics().flushChunk();
        this.resolution = props.metrics().resolution(); this.resolutionRegional = props.metrics().resolutionRegional();
        this.flushTimer = Timer.builder("esuo.heartbeat.flush").description("One flush of buffered heartbeats")
                .register(meters);
        this.flushed = DistributionSummary.builder("esuo.heartbeat.flush.branches")
                .description("Branches written per flush").register(meters);
        this.rowsWritten = Counter.builder("esuo.metrics.rows.written").description("Rows added to branch_metrics")
                .register(meters);
        this.rowsLost = Counter.builder("esuo.metrics.rows.lost").description("Metric rows lost to a failed write")
                .register(meters);
        Gauge.builder("esuo.heartbeats.buffered", buffer, HeartbeatBuffer::size)
                .description("Heartbeats waiting for the next flush").register(meters);
    }

    /**
     * A metrics row is due when most of the resolution has passed: heartbeats come every 10 s give or take a second,
     * and a strict comparison would drop every other one at a 10 s resolution.
     */
    static boolean due(@Nullable Instant last, Instant now, Duration resolution) {
        return last == null || !Duration.between(last, now).minus(resolution.multipliedBy(4).dividedBy(5)).isNegative();
    }

    /**
     * When each branch last had its metrics row (and, behind a regional hub, its content) written. A branch behind a
     * regional hub seen for the first time since the start counts as written at its own moment of the last resolution:
     * so they fall due a few at a time, after a start and ever after, not all in one flush once a minute — 250 000 of
     * them in one flush outlasted the offline threshold (scale design 9.2). Their content from before the start stays in
     * the database meanwhile; a new one has it written by its registration.
     */
    static final class Cadence {
        private final Map<String, Instant> last = new ConcurrentHashMap<>();

        boolean due(String branchId, Instant at, Duration resolution, boolean regional) {
            var l = regional ? last.computeIfAbsent(branchId, id -> at.minus(phase(id, resolution))) : last.get(branchId);
            return HeartbeatFlush.due(l, at, resolution);
        }

        /** A stable moment of the resolution for this branch, evenly spread over the branches. */
        static Duration phase(String branchId, Duration resolution) {
            int h = branchId.hashCode();                                // ids are sequential: mix them (MurmurHash3 fmix32)
            h ^= h >>> 16;
            h *= 0x85ebca6b;
            h ^= h >>> 13;
            h *= 0xc2b2ae35;
            h ^= h >>> 16;
            long window = Math.max(1, resolution.multipliedBy(4).dividedBy(5).toMillis());
            return Duration.ofMillis(Math.floorMod(h, window));
        }

        void written(String branchId, Instant at) {
            last.put(branchId, at);
        }
    }

    @Scheduled(fixedDelayString = "${esuo.center.metrics.flush-interval:5s}")
    public synchronized void run() {
        var batch = new ArrayList<>(buffer.drain().values());
        if (batch.isEmpty()) {
            return;
        }
        long started = System.nanoTime();
        seen(batch);
        int failed = 0;
        String failure = null;
        for (int from = 0; from < batch.size(); from += chunk) {
            var part = batch.subList(from, Math.min(from + chunk, batch.size()));
            try {
                write(part);
            } catch (RuntimeException e) {
                buffer.restore(part);                       // unless a fresher heartbeat of the branch came meanwhile
                failed += part.size();
                failure = e.toString();
            }
            part.replaceAll(hb -> null);                    // written chunks leave memory while the next ones are written
        }
        if (failed > 0) {
            log.warn("heartbeat flush: {} of {} branches kept for the next one: {}", failed, batch.size(), failure);
        }
        flushed.record(batch.size() - failed);
        flushTimer.record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    /**
     * The whole batch as "seen" first, a chunk per statement, before any content (scale design 9.2): at 250 000 branches
     * the content and metric rows of one flush take seconds to write, and "online" must neither wait for them nor fail
     * with them. A chunk that cannot be marked is only logged: its content write marks it too.
     */
    private void seen(List<Heartbeat> batch) {
        for (int from = 0; from < batch.size(); from += chunk) {
            var part = batch.subList(from, Math.min(from + chunk, batch.size()));
            try {
                registry.touch(part);
            } catch (RuntimeException e) {
                log.warn("heartbeat flush: {} branches not marked seen: {}", part.size(), e.toString());
            }
        }
    }

    /**
     * One chunk in one transaction: branches, then their metric rows under a savepoint. An ordinary branch has its
     * heartbeat written whole every time; a branch behind a regional hub has its content written at the regional
     * metrics resolution, and in between {@link #seen} is all (scale design 9.2): at 250 000 branches rewriting every
     * heartbeat JSON every 10 s was more than the database could write, and "online" lagged towards offline.
     */
    private void write(List<Heartbeat> part) {
        var rows = new ArrayList<MetricsRow>();
        var content = new ArrayList<Heartbeat>(part.size());
        for (var hb : part) {
            boolean due = cadence.due(hb.branchId(), hb.receivedAt(), hb.regional() ? resolutionRegional : resolution,
                    hb.regional());
            if (due || !hb.regional()) {
                content.add(hb);
            }
            if (due) {
                try {
                    rows.add(MetricsRow.of(hb.branchId(), json.readTree(hb.body()), hb.receivedAt()));
                } catch (RuntimeException e) {
                    log.warn("no metrics row for {}: {}", hb.branchId(), e.toString());
                }
            }
        }
        boolean written = Boolean.TRUE.equals(tx.execute(s -> {
            registry.saveHeartbeats(content);
            var savepoint = s.createSavepoint();
            try {
                metrics.insert(rows);
                s.releaseSavepoint(savepoint);
                return true;
            } catch (DataAccessException e) {
                s.rollbackToSavepoint(savepoint);
                log.warn("{} metric rows lost, heartbeats saved: {}", rows.size(), e.getMostSpecificCause().toString());
                return false;
            }
        }));
        if (written) {
            rows.forEach(r -> cadence.written(r.branchId(), r.ts()));
            rowsWritten.increment(rows.size());
        } else {
            rowsLost.increment(rows.size());
        }
    }

    /** The last few seconds of heartbeats are not lost on a clean shutdown. */
    @PreDestroy
    void flushOnShutdown() {
        run();
    }
}
