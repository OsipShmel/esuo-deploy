package honey.road.node.queue.scoring;

import honey.road.node.persistence.HandlingStatsRepository;
import honey.road.node.persistence.PolicyRepository;
import honey.road.node.queue.domain.ServiceWindow;
import honey.road.node.queue.domain.Ticket;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * {@code score = 1 / predicted_duration_sec}: among heads within {@code slack} the window takes the shorter service
 * and serves more clients (sect 16.2) [MVP-demo]. The estimate is the {@code handling_stats} cascade, falling back
 * to {@code default-service-duration-sec}; cascade level and sample count go to the call journal.
 * The deadline still bounds it: overdue tickets ignore score (QueueSelection).
 */
@Component
public class HeuristicScoringStrategy implements ScoringStrategy {
    static final String MODEL_VERSION = "handling_stats-v1";

    private final HandlingStatsRepository stats;
    private final PolicyRepository policies;

    public HeuristicScoringStrategy(HandlingStatsRepository stats, PolicyRepository policies) {
        this.stats = stats;
        this.policies = policies;
    }

    @Override
    public String name() { return "heuristic"; }

    @Override
    public ScoreResult score(Ticket ticket, ServiceWindow window, UUID employeeId) {
        var policy = policies.current();
        var forecast = stats.forecast(ticket.serviceId(), employeeId, ticket.clientCategory(), policy.heuristicMinSamples())
                .orElseGet(() -> new HandlingStatsRepository.Forecast(policy.defaultServiceDurationSec(),
                        HandlingStatsRepository.LEVEL_FALLBACK, 0));
        double seconds = Math.max(1.0, forecast.seconds());
        return new ScoreResult(1.0 / seconds, Duration.ofMillis(Math.round(seconds * 1000)), name(), MODEL_VERSION,
                forecast.level(), forecast.samples());
    }
}
