package honey.road.node.queue.scoring;

import honey.road.node.queue.domain.ServiceWindow;
import honey.road.node.queue.domain.Ticket;

import java.util.UUID;

/**
 * Оценка выгоды обслуживания талона в данном окне данным сотрудником (раздел 6.5).
 * Используется только для выбора между головами разных услуг внутри окна {@code slack};
 * порядок внутри одной услуги score не меняет. Queue Engine от реализации не зависит.
 */
public interface ScoringStrategy {
    String name();

    ScoreResult score(Ticket ticket, ServiceWindow window, UUID employeeId);
}
