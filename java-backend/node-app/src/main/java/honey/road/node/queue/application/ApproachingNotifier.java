package honey.road.node.queue.application;

import honey.road.contracts.event.Actor;
import honey.road.contracts.event.EventTypes;
import honey.road.contracts.event.TokenHash;
import honey.road.node.config.NodeProperties;
import honey.road.node.persistence.EventRepository;
import honey.road.node.persistence.PolicyRepository;
import honey.road.node.persistence.TicketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;

/**
 * "Your turn is approaching" (sect 4.7). For a waiting ticket that first matches
 * {@code position <= approaching-position-lte} or {@code eta <= approaching-eta-lte-min}, in one transaction:
 * set {@code notified_approaching_at} and write {@code notification.requested} with {@code dedup_key = ticket_id:approaching}.
 * The center delivers it; the client page gets the same event via node SSE.
 * <p>Skipped: tickets not yet eligible (early bookings, postponed) and tickets without ETA (no open window).
 */
@Service
public class ApproachingNotifier {
    public static final String KIND = "approaching";

    private final WaitTimeEstimator estimator;
    private final TicketRepository tickets;
    private final PolicyRepository policies;
    private final EventRepository events;
    private final NodeProperties node;
    private final Clock clock;

    public ApproachingNotifier(WaitTimeEstimator estimator, TicketRepository tickets, PolicyRepository policies,
                               EventRepository events, NodeProperties node, Clock clock) {
        this.estimator = estimator; this.tickets = tickets; this.policies = policies;
        this.events = events; this.node = node; this.clock = clock;
    }

    /** One pass; returns the number of notification requests created. */
    @Transactional
    public int scan() {
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        var policy = policies.current();
        var pending = tickets.waitingNotNotified(node.branchId());
        if (pending.isEmpty()) {
            return 0;
        }
        var estimates = estimator.estimateAll();
        var horizon = now.plus(policy.approachingEtaLteMin(), ChronoUnit.MINUTES);
        int requested = 0;
        for (var t : tickets.waiting(node.branchId())) {
            if (!pending.contains(t.id()) || t.eligibleFrom().isAfter(now)) {
                continue;
            }
            var e = estimates.get(t.id());
            if (e == null || e.eta() == null) {
                continue;
            }
            boolean byPosition = e.position() != null && e.position() <= policy.approachingPositionLte();
            boolean byEta = !e.eta().isAfter(horizon);
            if (!(byPosition || byEta) || !tickets.markApproachingNotified(t.id(), now)) {
                continue;
            }
            var data = new LinkedHashMap<String, Object>();
            data.put("kind", KIND);
            data.put("dedup_key", t.id() + ":" + KIND);
            data.put("ticket_id", t.id());
            data.put("token_hash", TokenHash.of(t.publicToken()));
            data.put("display_number", t.displayNumber());
            data.put("service_id", t.serviceId());
            data.put("status", t.status().name());
            data.put("position", e.position());
            data.put("eta", e.eta());
            data.put("eta_confidence", e.confidence());
            events.otherEvent(node.branchId(), EventTypes.AGGREGATE_TICKET, t.id(), t.version(),
                    EventTypes.NOTIFICATION_REQUESTED, Actor.system(), now, t.configVersion(), data, t.publicToken());
            requested++;
        }
        return requested;
    }
}
