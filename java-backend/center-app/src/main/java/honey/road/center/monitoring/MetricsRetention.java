package honey.road.center.monitoring;

import honey.road.center.config.CenterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;

/**
 * Drops {@code branch_metrics} older than the retention (48 h) every ten minutes. Under an advisory lock, so that with
 * several center replicas only one of them deletes.
 */
@Component
public class MetricsRetention {
    private static final Logger log = LoggerFactory.getLogger(MetricsRetention.class);

    private final BranchMetrics metrics;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Duration retention;
    private final Clock clock;

    public MetricsRetention(BranchMetrics metrics, JdbcClient jdbc, PlatformTransactionManager transactions,
                            CenterProperties props, Clock clock) {
        this.metrics = metrics; this.jdbc = jdbc; this.tx = new TransactionTemplate(transactions);
        this.retention = props.metrics().retention(); this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    public void run() {
        try {
            tx.executeWithoutResult(s -> {
                if (Boolean.TRUE.equals(jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtext('esuo.metrics.retention'))")
                        .query(Boolean.class).single())) {
                    int deleted = metrics.deleteOlderThan(clock.instant().minus(retention));
                    log.debug("branch_metrics retention: {} rows older than {} deleted", deleted, retention);
                }
            });
        } catch (RuntimeException e) {
            log.warn("branch_metrics retention failed: {}", e.toString());
        }
    }
}
