package honey.road.node.queue.scoring;

import honey.road.node.config.NodeProperties;
import honey.road.node.model.DurationModel;
import honey.road.node.queue.domain.ServiceWindow;
import honey.road.node.queue.domain.Ticket;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneId;
import java.util.UUID;

/**
 * {@code score = 1 / predicted_duration_sec}: among heads within {@code slack} the window takes the shorter
 * service and serves more clients (sect 16.2). The forecast comes from {@link DurationModel}, which means the
 * model coefficients when the center has published them and the {@code handling_stats} cascade otherwise —
 * the strategy itself does not change between those cases, and neither does the shape of the journal entry.
 * The deadline still bounds it: overdue tickets ignore score (QueueSelection).
 *
 * <p>Uses {@code p50}, not {@code mean}: picking one ticket needs only the order of candidates, and the median
 * is the point forecast that minimises absolute error (ml-model.md 3.2). Sums of work — ETA, hour capacity —
 * use {@code mean} instead, and that difference is deliberate.
 */
@Component
public class HeuristicScoringStrategy implements ScoringStrategy {
    private final DurationModel durations;
    private final Clock clock;
    private final ZoneId zone;

    public HeuristicScoringStrategy(DurationModel durations, Clock clock, NodeProperties node) {
        this.durations = durations;
        this.clock = clock;
        this.zone = ZoneId.of(node.timezone());
    }

    @Override
    public String name() { return "heuristic"; }

    @Override
    public ScoreResult score(Ticket ticket, ServiceWindow window, UUID employeeId) {
        int hour = clock.instant().atZone(zone).getHour();
        var prediction = durations.predict(ticket.serviceId(), ticket.clientCategory(), employeeId, hour,
                window == null ? null : window.id());
        double seconds = Math.max(1.0, prediction.p50Seconds());
        return new ScoreResult(1.0 / seconds, prediction.p50(), name(), prediction.modelVersion(),
                prediction.basis(), prediction.samples());
    }
}
