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
 * Уведомление «очередь приближается» (4.7, Q3). Для ожидающего талона, который впервые попал под правило
 * {@code position <= approaching-position-lte} или {@code eta <= approaching-eta-lte-min}, в одной транзакции:
 * ставится флаг {@code notified_approaching_at} и пишется событие {@code notification.requested}
 * с {@code dedup_key = ticket_id:approaching}. Доставку выполняет центр; страница клиента получает то же
 * событие по SSE ноды.
 * <p>Не уведомляются: талоны, ещё не допущенные к вызову (запись раньше времени, отложенные), и талоны без ETA
 * (нет открытого окна с их услугой) — «приближения» там нет.
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

    /** Один проход; возвращает число созданных запросов уведомлений. */
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
