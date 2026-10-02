package honey.road.node.monitoring;

import honey.road.node.api.sse.SseHub;
import honey.road.node.messaging.nats.LeafLinkMonitor;
import honey.road.node.messaging.nats.NatsGateway;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code sys} block of the heartbeat (design 6.1): the state of the branch server itself. Every source is read on
 * its own; a source that fails or does not exist leaves its fields out, the rest of the block still goes.
 * Rates and counters cover the time since the previous sample, i.e. one heartbeat interval.
 */
@Component
public class SysMetrics {
    private static final Logger log = LoggerFactory.getLogger(SysMetrics.class);
    private static final Duration DB_SIZE_TTL = Duration.ofSeconds(60);
    private static final long MB = 1024 * 1024;

    private final MeterReadings meters;
    private final HttpStatsFilter http;
    private final SseHub sse;
    private final LeafLinkMonitor leaf;
    private final NatsGateway nats;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Path diskDir;
    private final Clock clock;

    private @Nullable Instant lastSample;
    private @Nullable Long dbSizeMb;
    private @Nullable Instant dbSizeAt;

    public SysMetrics(MeterRegistry registry, HttpStatsFilter http, SseHub sse, LeafLinkMonitor leaf, NatsGateway nats,
                      JdbcTemplate jdbc, PlatformTransactionManager transactions, @Value("${esuo.logs.file:}") String logFile,
                      @Qualifier("realClock") Clock clock) {
        this.meters = new MeterReadings(registry);
        this.http = http; this.sse = sse; this.leaf = leaf; this.nats = nats; this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        var parent = logFile.isBlank() ? null : Path.of(logFile).toAbsolutePath().getParent();
        this.diskDir = parent == null ? Path.of("").toAbsolutePath() : parent;   // on a branch PC the logs share the disk with the DB
        this.clock = clock;
    }

    public synchronized Map<String, Object> sample() {
        var now = clock.instant();
        var runtime = ManagementFactory.getRuntimeMXBean();
        var sys = new LinkedHashMap<String, Object>();
        sys.put("sent_at_system", now);                          // real time even under the demo clock: clock skew
        sys.put("started_at", Instant.ofEpochMilli(runtime.getStartTime()));
        sys.put("uptime_sec", runtime.getUptime() / 1000);
        sys.put("app_version", LifecycleEvents.version());
        sys.put("cpu_count", Runtime.getRuntime().availableProcessors());
        safely("meters", () -> meters.putInto(sys));
        safely("db size", () -> putDbSize(sys, now));
        safely("disk", () -> putDisk(sys));
        safely("http", () -> putHttp(sys, now));
        safely("sse", () -> sys.put("sse_clients", sse.connections()));
        sys.put("leaf_connected", leaf.linked());
        var rtt = leaf.rttMs();
        if (rtt != null) {
            sys.put("leaf_rtt_ms", Math.round(rtt * 100) / 100.0);
        }
        long reconnects = nats.reconnects();
        if (reconnects >= 0) {
            sys.put("nats_reconnects", reconnects);
        }
        lastSample = now;
        return sys;
    }

    /** Once a minute, with a short timeout: a locked database must not hold up the heartbeat. Stale beats nothing. */
    private void putDbSize(Map<String, Object> sys, Instant now) {
        if (dbSizeAt == null || Duration.between(dbSizeAt, now).compareTo(DB_SIZE_TTL) >= 0) {
            try {
                var bytes = tx.execute(s -> {
                    jdbc.execute("SET LOCAL statement_timeout = '2s'");
                    return jdbc.queryForObject("SELECT pg_database_size(current_database())", Long.class);
                });
                if (bytes != null) {
                    dbSizeMb = bytes / MB;
                }
            } catch (RuntimeException e) {
                log.debug("database size unavailable: {}", e.toString());
            }
            dbSizeAt = now;
        }
        if (dbSizeMb != null) {
            sys.put("db_size_mb", dbSizeMb);
        }
    }

    private void putDisk(Map<String, Object> sys) {
        var dir = diskDir.toFile();
        long total = dir.getTotalSpace();
        if (total > 0) {
            sys.put("disk_free_mb", dir.getUsableSpace() / MB);
            sys.put("disk_total_mb", total / MB);
        }
    }

    private void putHttp(Map<String, Object> sys, Instant now) {
        var w = http.drain();
        sys.put("http_requests", w.count());
        if (lastSample != null) {
            double seconds = Math.max(Duration.between(lastSample, now).toMillis(), 1) / 1000.0;
            sys.put("http_rps", Math.round(w.count() / seconds * 10) / 10.0);
        }
        if (w.p95Ms() != null) {
            sys.put("http_p95_ms", w.p95Ms());
        }
        sys.put("http_5xx_rate", w.count() == 0 ? 0.0 : Math.round(1000.0 * w.errors5xx() / w.count()) / 1000.0);
    }

    private static void safely(String what, Runnable source) {
        try {
            source.run();
        } catch (RuntimeException e) {
            log.debug("sys block without {}: {}", what, e.toString());
        }
    }
}
