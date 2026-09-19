package honey.road.node.queue.application;

import honey.road.node.persistence.HandlingStatsRepository;
import honey.road.node.persistence.PolicyRepository;
import honey.road.node.persistence.TicketRepository;
import honey.road.node.persistence.WindowRepository;
import honey.road.node.queue.domain.Ticket;
import honey.road.node.queue.domain.TicketStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Позиция и ожидаемое время вызова [MVP, упрощённо; раздел 6.8].
 * Позиция — число талонов перед данным в его услуге в порядке EDF.
 * ETA = now + ceil((позиция + 1) / открытых_окон_услуги) × средняя длительность услуги.
 * Нет открытых окон с услугой — ETA не определено (S2).
 * <p>Точную симуляцию по окнам с учётом скоринга делает A (WaitTimeEstimator из 6.8), интерфейс тот же.
 */
@Component
public class WaitTimeEstimator {
    private static final int MIN_SAMPLES = 5;

    private final TicketRepository tickets;
    private final WindowRepository windows;
    private final HandlingStatsRepository stats;
    private final PolicyRepository policies;
    private final Clock clock;

    public WaitTimeEstimator(TicketRepository tickets, WindowRepository windows, HandlingStatsRepository stats,
                             PolicyRepository policies, Clock clock) {
        this.tickets = tickets; this.windows = windows; this.stats = stats; this.policies = policies; this.clock = clock;
    }

    public record Estimate(Integer position, Instant eta, String confidence) {
        static final Estimate NONE = new Estimate(null, null, null);
    }

    public Estimate estimate(Ticket t) {
        if (t.status() == TicketStatus.booked) {
            return new Estimate(null, t.slotStart(), "slot");
        }
        if (t.status() != TicketStatus.waiting) {
            return Estimate.NONE;
        }
        int ahead = tickets.aheadOf(t);
        int open = windows.openWindowsFor(t.serviceId());
        if (open == 0) {
            return new Estimate(ahead, null, "none");
        }
        var avg = stats.averageForService(t.serviceId(), MIN_SAMPLES);
        double seconds = avg.orElse((double) policies.current().defaultServiceDurationSec());
        long rounds = (ahead + open) / open;                      // ceil((ahead+1)/open)
        var now = clock.instant();
        var eta = now.plus(Duration.ofSeconds((long) (rounds * seconds)));
        if (t.eligibleFrom() != null && t.eligibleFrom().isAfter(eta)) {
            eta = t.eligibleFrom();
        }
        return new Estimate(ahead, eta, avg.isPresent() ? "medium" : "low");
    }
}
