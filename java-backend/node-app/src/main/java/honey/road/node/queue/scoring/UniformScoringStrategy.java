package honey.road.node.queue.scoring;

import honey.road.node.persistence.PolicyRepository;
import honey.road.node.queue.domain.ServiceWindow;
import honey.road.node.queue.domain.Ticket;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/** {@code value = 1} for all: plain EDF. [MVP] */
@Component
public class UniformScoringStrategy implements ScoringStrategy {
    private final PolicyRepository policies;

    public UniformScoringStrategy(PolicyRepository policies) { this.policies = policies; }

    @Override
    public String name() { return "uniform"; }

    @Override
    public ScoreResult score(Ticket ticket, ServiceWindow window, UUID employeeId) {
        return new ScoreResult(1.0, Duration.ofSeconds(policies.current().defaultServiceDurationSec()), name(), "const");
    }
}
