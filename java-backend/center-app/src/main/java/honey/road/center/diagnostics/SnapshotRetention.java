package honey.road.center.diagnostics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;

/** Drops diagnostics snapshots older than 30 days, hourly, under an advisory lock (one replica deletes). */
@Component
public class SnapshotRetention {
    private static final Logger log = LoggerFactory.getLogger(SnapshotRetention.class);
    static final Duration RETENTION = Duration.ofDays(30);

    private final SnapshotStore store;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    SnapshotRetention(SnapshotStore store, JdbcClient jdbc, PlatformTransactionManager transactions, Clock clock) {
        this.store = store; this.jdbc = jdbc; this.tx = new TransactionTemplate(transactions); this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT2M")
    public void run() {
        try {
            tx.executeWithoutResult(s -> {
                if (Boolean.TRUE.equals(jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtext('esuo.diagnostics.retention'))")
                        .query(Boolean.class).single())) {
                    log.debug("diagnostics retention: {} snapshots deleted", store.deleteOlderThan(clock.instant().minus(RETENTION)));
                }
            });
        } catch (RuntimeException e) {
            log.warn("diagnostics retention failed: {}", e.toString());
        }
    }
}
