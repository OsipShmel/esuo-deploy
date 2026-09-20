package honey.road.node.queue.scoring;

import honey.road.node.queue.domain.ServiceWindow;
import honey.road.node.queue.domain.Ticket;

import java.util.UUID;

/**
 * Benefit of serving a ticket in this window by this employee (sect 6.5). Used only to choose between heads of
 * different services within {@code slack}; order within one service is unaffected.
 */
public interface ScoringStrategy {
    String name();

    ScoreResult score(Ticket ticket, ServiceWindow window, UUID employeeId);
}
