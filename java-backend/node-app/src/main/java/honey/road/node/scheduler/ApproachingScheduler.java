package honey.road.node.scheduler;

import honey.road.node.queue.application.ApproachingNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic "approaching" scan (sect 4.7). Stateless: after a restart the first pass continues from the
 * {@code notified_approaching_at} flags. Disabled by {@code esuo.notifications.approaching.enabled=false}
 * (integration tests call {@code scan()} themselves).
 */
@Component
@ConditionalOnProperty(name = "esuo.notifications.approaching.enabled", havingValue = "true", matchIfMissing = true)
public class ApproachingScheduler {
    private static final Logger log = LoggerFactory.getLogger(ApproachingScheduler.class);

    private final ApproachingNotifier notifier;

    public ApproachingScheduler(ApproachingNotifier notifier) {
        this.notifier = notifier;
    }

    @Scheduled(fixedDelayString = "${esuo.timers.interval:5s}", initialDelayString = "20s")
    public void scan() {
        try {
            int n = notifier.scan();
            if (n > 0) {
                log.info("approaching notifications requested: {}", n);
            }
        } catch (RuntimeException e) {
            log.error("approaching scan failed", e);
        }
    }
}
