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
 * Writes buffered heartbeats every few seconds (design 6.2): one transaction upserts {@code branches} and appends
 * {@code branch_metrics}, instead of a transaction per heartbeat. A failed write puts the batch back unless a fresher
 * heartbeat replaced it; the overview lags by at most one flush interval, far below the 30 s offline threshold. The
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
    private final Duration resolution;
    private final Duration resolutionRegional;
    private final Map<String, Instant> lastMetricAt = new ConcurrentHashMap<>();

    private final Timer flushTimer;
    private final DistributionSummary flushed;
    private final Counter rowsWritten;
    private final Counter rowsLost;

    public HeartbeatFlush(HeartbeatBuffer buffer, BranchRegistry registry, BranchMetrics metrics,
                          PlatformTransactionManager transactions, JsonMapper json, CenterProperties props,
                          MeterRegistry meters) {
        this.buffer = buffer; this.registry = registry; this.metrics = metrics;
        this.tx = new TransactionTemplate(transactions); this.json = json;
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

    @Scheduled(fixedDelayString = "${esuo.center.metrics.flush-interval:5s}")
    public synchronized void run() {
        var batch = List.copyOf(buffer.drain().values());
        if (batch.isEmpty()) {
            return;
        }
        long started = System.nanoTime();
        var rows = new ArrayList<MetricsRow>();
        for (var hb : batch) {
            if (due(lastMetricAt.get(hb.branchId()), hb.receivedAt(), hb.regional() ? resolutionRegional : resolution)) {
                try {
                    rows.add(MetricsRow.of(hb.branchId(), json.readTree(hb.json()), hb.receivedAt()));
                } catch (RuntimeException e) {
                    log.warn("no metrics row for {}: {}", hb.branchId(), e.toString());
                }
            }
        }
        try {
            boolean written = Boolean.TRUE.equals(tx.execute(s -> {
                registry.saveHeartbeats(batch);
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
                rows.forEach(r -> lastMetricAt.put(r.branchId(), r.ts()));
                rowsWritten.increment(rows.size());
            } else {
                rowsLost.increment(rows.size());
            }
            flushed.record(batch.size());
        } catch (RuntimeException e) {
            buffer.restore(batch);
            log.warn("heartbeat flush failed, {} branches kept for the next one: {}", batch.size(), e.toString());
        } finally {
            flushTimer.record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    /** The last few seconds of heartbeats are not lost on a clean shutdown. */
    @PreDestroy
    void flushOnShutdown() {
        run();
    }
}
