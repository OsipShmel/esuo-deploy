package honey.road.node.scheduler;

import honey.road.contracts.event.Actor;
import honey.road.node.config.NodeProperties;
import honey.road.node.persistence.PolicyRepository;
import honey.road.node.persistence.TicketRepository;
import honey.road.node.queue.application.TicketCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

/**
 * Queue timers: periodic jobs over DB fields, no in-memory state (S12), so overdue timeouts are handled by the
 * first pass after a restart.
 * <ul>
 *   <li>S5: the called client did not show up within {@code call_timeout_sec} → recall; after {@code max_recalls} → no_show.</li>
 *   <li>S8: booking without check-in by {@code slot_start + max(grace, retention)} → no_show.</li>
 * </ul>
 */
@Component
public class QueueTimers {
    /** Timer period: {@code esuo.timers.interval} (5 s), 1 s in demo mode. */
    static final String INTERVAL = "#{${esuo.demo-mode:false} ? '1s' : '${esuo.timers.interval:5s}'}";

    private static final Logger log = LoggerFactory.getLogger(QueueTimers.class);
    private static final int BATCH = 50;

    private final TicketRepository tickets;
    private final TicketCommands commands;
    private final PolicyRepository policies;
    private final NodeProperties node;
    private final TransactionTemplate tx;
    private final Clock clock;

    public QueueTimers(TicketRepository tickets, TicketCommands commands, PolicyRepository policies,
                       NodeProperties node, TransactionTemplate tx, Clock clock) {
        this.tickets = tickets; this.commands = commands; this.policies = policies;
        this.node = node; this.tx = tx; this.clock = clock;
    }

    @Scheduled(fixedDelayString = QueueTimers.INTERVAL, initialDelayString = "10s")
    public void callTimeouts() {
        try {
            tx.executeWithoutResult(s -> {
                var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
                var threshold = now.minusSeconds(policies.current().callTimeoutSec());
                for (var t : tickets.lockCallTimedOut(node.branchId(), threshold, BATCH)) {
                    var r = commands.recallLocked(t, Actor.scheduler(), now);
                    log.info("call timeout ticket={} -> {} call_count={}", t.displayNumber(), r.status(), r.callCount());
                }
            });
        } catch (RuntimeException e) {
            log.error("call timeout pass failed", e);
        }
    }

    @Scheduled(fixedDelayString = QueueTimers.INTERVAL, initialDelayString = "15s")
    public void expireBookings() {
        try {
            tx.executeWithoutResult(s -> {
                var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
                var policy = policies.current();
                var threshold = now.minus(Math.max(policy.graceLateMin(), policy.lateCheckinRetentionMin()), ChronoUnit.MINUTES);
                for (var t : tickets.lockBookedBefore(node.branchId(), threshold, BATCH)) {
                    commands.noShowLocked(t, Actor.scheduler(), now, "appointment_expired");
                    log.info("appointment expired ticket={}", t.displayNumber());
                }
            });
        } catch (RuntimeException e) {
            log.error("booking expiry pass failed", e);
        }
    }
}
