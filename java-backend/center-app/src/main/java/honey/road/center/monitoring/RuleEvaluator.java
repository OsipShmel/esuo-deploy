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
import java.util.Comparator;

/**
 * Runs the monitoring rules over {@code branch_metrics} and replaces {@code branch_alerts} (design 6.4). Under an
 * advisory lock, so that with several center replicas one evaluates and the others skip the turn. In demo mode the
 * durations of the rules shrink by {@code demo-duration-scale}, so a rule of five minutes fires within a demo.
 */
@Component
public class RuleEvaluator {
    private static final Logger log = LoggerFactory.getLogger(RuleEvaluator.class);
    private static final Duration MARGIN = Duration.ofMinutes(1);

    private final MonitoringConfig config;
    private final BranchMetrics metrics;
    private final BranchAlerts alerts;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final double scale;
    private final Clock clock;

    public RuleEvaluator(MonitoringConfig config, BranchMetrics metrics, BranchAlerts alerts, JdbcClient jdbc,
                         PlatformTransactionManager transactions, CenterProperties props, Clock clock) {
        this.config = config; this.metrics = metrics; this.alerts = alerts; this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.scale = props.demoMode() ? config.demoDurationScale() : 1.0;
        this.clock = clock;
        log.info("monitoring rules: {} loaded, evaluated every {}, duration scale {}", config.rules().size(),
                config.evaluateEvery(), scale);
    }

    @Scheduled(fixedDelayString = "${esuo.center.monitoring.evaluate-every:15s}",
            initialDelayString = "${esuo.center.monitoring.evaluate-every:15s}")
    public void run() {
        try {
            tx.executeWithoutResult(s -> {
                if (!Boolean.TRUE.equals(jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtext('esuo.monitoring.rules'))")
                        .query(Boolean.class).single())) {
                    return;
                }
                var now = clock.instant();
                var span = config.rules().stream().map(MonitoringConfig.Rule::span).max(Comparator.naturalOrder())
                        .orElse(Duration.ZERO);
                var history = metrics.window(now.minus(MonitoringRules.scaled(span, scale)).minus(MARGIN));
                alerts.sync(MonitoringRules.evaluate(history, config.rules(), now, scale), now);
            });
        } catch (RuntimeException e) {
            log.warn("monitoring rules evaluation failed: {}", e.toString());
        }
    }
}
