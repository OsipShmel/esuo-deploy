package honey.road.center.centralconfig;

import honey.road.center.config.CenterProperties;
import honey.road.center.network.BranchOnboarding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Keeps online branches on their published config (sect 4.8, item 5):
 * <ul>
 *   <li>no published version yet → assemble and publish one (also done right on the first heartbeat);</li>
 *   <li>heartbeat {@code config_version} counts as applied (as {@code config.applied} does);</li>
 *   <li>published but not applied for {@code resend-after} since the last send → resend with a new {@code Nats-Msg-Id};</li>
 *   <li>node reports a version above the published one → move the sequence past it so the next change wins.</li>
 * </ul>
 * Offline branches are skipped: the message waits in {@code CONFIG} and is sourced when the link is back. So are
 * branches still queued in {@link BranchOnboarding}, which publishes their first version itself: otherwise a wave of
 * new branches got a second version from here before the first one was even sent.
 */
@Component
class ConfigReconciler {
    private static final Logger log = LoggerFactory.getLogger(ConfigReconciler.class);
    static final Duration RESEND_AFTER = Duration.ofSeconds(60);

    private final ConfigStore store;
    private final ConfigPublisher publisher;
    private final CenterProperties props;
    private final Clock clock;
    private final BranchOnboarding onboarding;

    ConfigReconciler(ConfigStore store, ConfigPublisher publisher, CenterProperties props, Clock clock,
                     BranchOnboarding onboarding) {
        this.store = store; this.publisher = publisher; this.props = props; this.clock = clock;
        this.onboarding = onboarding;
    }

    @Scheduled(fixedDelayString = "PT15S", initialDelayString = "PT20S")
    void reconcile() {
        var now = clock.instant();
        for (var s : store.states()) {
            try {
                if (Duration.between(s.lastHeartbeatAt(), now).compareTo(props.offlineAfter()) > 0
                        || onboarding.pending(s.id())) {
                    continue;
                }
                if (s.published() == null) {
                    publisher.publishTo(s.id(), "system");
                    continue;
                }
                if (s.heartbeatConfigVersion() > s.published()) {
                    // Center DB was reset or someone published to CONFIG directly (smoke scripts): the next center
                    // change must get a higher number, the node's current config is left alone.
                    store.advanceVersionPast(s.heartbeatConfigVersion());
                }
                if (s.heartbeatConfigVersion() >= s.published()) {
                    store.markApplied(s.id(), s.heartbeatConfigVersion(), now);
                    continue;
                }
                boolean applied = s.applied() != null && s.applied() >= s.published();
                boolean due = s.sentAt() == null || Duration.between(s.sentAt(), now).compareTo(RESEND_AFTER) >= 0;
                if (!applied && due) {
                    publisher.send(s.id(), s.published(), s.sentAt() != null);
                }
            } catch (RuntimeException e) {
                log.warn("config reconcile for {} failed: {}", s.id(), e.toString());
            }
        }
    }
}
