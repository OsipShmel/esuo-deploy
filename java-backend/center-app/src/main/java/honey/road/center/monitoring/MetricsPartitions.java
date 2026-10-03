package honey.road.center.monitoring;

import honey.road.center.config.CenterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Hourly partitions of {@code branch_metrics} (scale design 5.1): a day ahead is always there, old hours go as whole
 * partitions (instant at any volume, no table bloat), and over {@code max-size} the oldest hours go early so that the
 * metrics never fill the disk. The last hour is never dropped: the rules look that far back.
 *
 * <p>One transaction per run under an advisory lock (center replicas take turns); each step under a savepoint with a
 * short {@code lock_timeout}: a step behind a long Grafana query gives up within seconds instead of holding the
 * heartbeat inserts queued behind its lock, and is retried by the next run. Rows of an hour without its partition land
 * in the default partition and are moved in when the partition is created.
 */
@Component
public class MetricsPartitions {
    private static final Logger log = LoggerFactory.getLogger(MetricsPartitions.class);
    static final String PREFIX = "branch_metrics_p";
    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter HOUR_NAME = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final Duration AHEAD = Duration.ofHours(24);
    private static final Duration KEPT_FOR_RULES = Duration.ofHours(1);
    /** An hour with more stray rows than this stays in the default partition: moving it would block inserts too long. */
    private static final int MAX_MOVED = 100_000;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Duration retention;
    private final DataSize maxSize;
    private final Clock clock;

    @Autowired
    public MetricsPartitions(JdbcClient jdbc, PlatformTransactionManager transactions, CenterProperties props, Clock clock) {
        this(jdbc, transactions, props.metrics().retention(), props.metrics().maxSize(), clock);
    }

    MetricsPartitions(JdbcClient jdbc, PlatformTransactionManager transactions, Duration retention, DataSize maxSize,
                      Clock clock) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(transactions);
        this.retention = retention; this.maxSize = maxSize; this.clock = clock;
    }

    static String suffix(Instant hour) {
        return SUFFIX.format(hour);
    }

    private record Partition(String name, Instant from, long bytes) {
        Instant to() {
            return from.plus(Duration.ofHours(1));
        }
    }

    @Scheduled(fixedDelayString = "${esuo.center.metrics.partitions-every:10m}",
            initialDelayString = "${esuo.center.metrics.partitions-initial-delay:0s}")
    public void run() {
        try {
            tx.executeWithoutResult(status -> {
                jdbc.sql("SET LOCAL lock_timeout = '30s'").update();
                jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('esuo.metrics.partitions')) IS NULL").query(Boolean.class).single();
                jdbc.sql("SET LOCAL lock_timeout = '2s'").update();
                maintain(status, clock.instant());
            });
        } catch (RuntimeException e) {
            log.warn("branch_metrics partition maintenance failed: {}", e.toString());
        }
    }

    private void maintain(TransactionStatus status, Instant now) {
        var existing = partitions();
        var names = existing.stream().map(Partition::name).toList();
        var hour = now.truncatedTo(ChronoUnit.HOURS);
        for (var h = hour; !h.isAfter(hour.plus(AHEAD)); h = h.plus(Duration.ofHours(1))) {
            var from = h;
            if (!names.contains(PREFIX + suffix(from))) {
                step(status, "create " + PREFIX + suffix(from), s -> create(from));
            }
        }
        var cut = now.minus(retention);
        var kept = new ArrayList<Partition>();
        for (var p : existing) {
            if (!p.to().isAfter(cut)) {
                step(status, "drop " + p.name(), s -> drop(p, "older than " + retention));
            } else {
                kept.add(p);
            }
        }
        step(status, "clean the default partition", s -> jdbc.sql("DELETE FROM branch_metrics_default WHERE ts < :cut")
                .param("cut", Timestamp.from(cut)).update());
        long total = jdbc.sql("SELECT pg_total_relation_size('branch_metrics_default')").query(Long.class).single()
                + kept.stream().mapToLong(Partition::bytes).sum();
        for (var p : kept) {                                         // oldest first
            if (total <= maxSize.toBytes() || p.to().isAfter(now.minus(KEPT_FOR_RULES))) {
                break;
            }
            if (step(status, "drop " + p.name(), s -> drop(p, "metrics over " + maxSize + ", before the retention of " + retention))) {
                total -= p.bytes();
            }
        }
        if (total > maxSize.toBytes()) {
            log.warn("branch_metrics is {} MB, over {} even with only the last hour kept", total >> 20, maxSize);
        }
    }

    private List<Partition> partitions() {
        return jdbc.sql("""
                        SELECT c.relname, pg_total_relation_size(c.oid) AS bytes
                        FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                        WHERE i.inhparent = 'branch_metrics'::regclass AND c.relname LIKE 'branch\\_metrics\\_p%'
                        ORDER BY c.relname""")
                .query((rs, n) -> {
                    var name = rs.getString("relname");
                    var from = LocalDateTime.parse(name.substring(PREFIX.length()) + "00", HOUR_NAME).toInstant(ZoneOffset.UTC);
                    return new Partition(name, from, rs.getLong("bytes"));
                })
                .list();
    }

    private void create(Instant from) {
        var name = PREFIX + suffix(from);
        var range = "FROM ('" + from + "') TO ('" + from.plus(Duration.ofHours(1)) + "')";
        long stray = jdbc.sql("SELECT count(*) FROM (SELECT 1 FROM branch_metrics_default WHERE ts >= :from AND ts < :to LIMIT "
                        + (MAX_MOVED + 1) + ") s")
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(from.plus(Duration.ofHours(1))))
                .query(Long.class).single();
        if (stray > MAX_MOVED) {
            log.warn("{} not created: over {} rows of that hour are in the default partition, they stay there", name, MAX_MOVED);
            return;
        }
        if (stray > 0) {
            jdbc.sql("LOCK TABLE branch_metrics IN ACCESS EXCLUSIVE MODE").update();
            jdbc.sql("CREATE TEMP TABLE branch_metrics_moving ON COMMIT DROP AS "
                    + "WITH m AS (DELETE FROM branch_metrics_default WHERE ts >= '" + from + "' AND ts < '"
                    + from.plus(Duration.ofHours(1)) + "' RETURNING *) SELECT * FROM m").update();
        }
        jdbc.sql("CREATE TABLE " + name + " PARTITION OF branch_metrics FOR VALUES " + range).update();
        if (stray > 0) {
            jdbc.sql("INSERT INTO branch_metrics SELECT * FROM branch_metrics_moving").update();
            jdbc.sql("DROP TABLE branch_metrics_moving").update();
            log.info("{} created, {} rows moved in from the default partition", name, stray);
        } else {
            log.debug("{} created", name);
        }
    }

    private void drop(Partition p, String why) {
        jdbc.sql("DROP TABLE " + p.name()).update();
        log.info("{} dropped ({} MB): {}", p.name(), p.bytes() >> 20, why);
    }

    /** One step under a savepoint: its failure is logged and rolled back alone, the run goes on. */
    private boolean step(TransactionStatus status, String what, Consumer<TransactionStatus> body) {
        var savepoint = status.createSavepoint();
        try {
            body.accept(status);
            status.releaseSavepoint(savepoint);
            return true;
        } catch (DataAccessException e) {
            status.rollbackToSavepoint(savepoint);
            log.warn("branch_metrics partitions: {} failed, retried next run: {}", what,
                    e.getMostSpecificCause().getMessage());
            return false;
        }
    }
}
