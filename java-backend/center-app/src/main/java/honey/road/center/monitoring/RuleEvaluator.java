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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs the monitoring rules ({@link RuleQuery}) and replaces {@code branch_alerts} (design 6.4, scale design 5.1).
 * Under an advisory lock, so that with several center replicas one evaluates and the others skip the turn; with a
 * statement timeout, so that a pass never piles up behind itself. In demo mode the durations of the rules shrink by
 * {@code demo-duration-scale}, so a rule of five minutes fires within a demo.
 *
 * <p>FRESH — how old the last point may be, and the longest gap within a run — is two minutes or three metric
 * intervals, whichever is longer: at one row a minute (the swarm) one lost heartbeat must not break a run.
 */
@Component
public class RuleEvaluator {
    private static final Logger log = LoggerFactory.getLogger(RuleEvaluator.class);
    private static final Duration MIN_FRESH = Duration.ofMinutes(2);
    private static final String STATEMENT_TIMEOUT = "30s";

    private final MonitoringConfig config;
    private final RuleQuery query;
    private final BranchAlerts alerts;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RuleEvaluator(MonitoringConfig config, BranchAlerts alerts, JdbcClient jdbc,
                         PlatformTransactionManager transactions, CenterProperties props, Clock clock) {
        this.config = config; this.alerts = alerts; this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
        double scale = props.demoMode() ? config.demoDurationScale() : 1.0;
        var resolution = props.metrics().resolution().compareTo(props.metrics().resolutionRegional()) > 0
                ? props.metrics().resolution() : props.metrics().resolutionRegional();
        var fresh = resolution.multipliedBy(3).compareTo(MIN_FRESH) > 0 ? resolution.multipliedBy(3) : MIN_FRESH;
        this.query = new RuleQuery(jdbc, config.rules(), scale, fresh);
        log.info("monitoring rules: {} loaded, evaluated every {}, duration scale {}, fresh {}", config.rules().size(),
                config.evaluateEvery(), scale, fresh);
    }

    @Scheduled(fixedDelayString = "${esuo.center.monitoring.evaluate-every:15s}",
            initialDelayString = "${esuo.center.monitoring.evaluate-every:15s}")
    public void run() {
        long started = System.nanoTime();
        try {
            var count = tx.execute(s -> {
                jdbc.sql("SET LOCAL statement_timeout = '" + STATEMENT_TIMEOUT + "'").update();
                if (!Boolean.TRUE.equals(jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtext('esuo.monitoring.rules'))")
                        .query(Boolean.class).single())) {
                    return null;
                }
                var now = clock.instant();
                var result = alerts(query.fired(now), alerts.sinceByKey(), now);
                alerts.sync(result, now);
                return result.size();
            });
            var took = Duration.ofNanos(System.nanoTime() - started);
            if (count != null && took.compareTo(config.evaluateEvery()) > 0) {
                log.warn("monitoring rules pass took {} ms, longer than its period {}: {} alerts", took.toMillis(),
                        config.evaluateEvery(), count);
            } else if (count != null) {
                log.debug("monitoring rules pass: {} alerts in {} ms", count, took.toMillis());
            }
        } catch (RuntimeException e) {
            log.warn("monitoring rules evaluation failed after {} ms: {}", Duration.ofNanos(System.nanoTime() - started).toMillis(),
                    e.toString());
        }
    }

    /**
     * Alerts from what fired. A continuing alert keeps the start it had, so "уже {for} мин" keeps growing past the
     * window the query looks at; a restart ({@code decrease}) is an event, its time is the latest restart's.
     *
     * @param kept {@code since} of the alerts stored now, by {@code branch/rule}
     */
    static List<MonitoringRules.Alert> alerts(List<RuleQuery.Fired> fired, Map<String, Instant> kept, Instant now) {
        var out = new ArrayList<MonitoringRules.Alert>(fired.size());
        for (var f : fired) {
            var since = "decrease".equals(f.rule().kind()) ? f.since()
                    : kept.getOrDefault(f.branchId() + "/" + f.rule().id(), f.since());
            out.add(MonitoringRules.alert(f.branchId(), f.rule(), f.value(), since, now, f.zone()));
        }
        return out;
    }
}
