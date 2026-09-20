package honey.road.node.queue.application;

import honey.road.node.config.NodeProperties;
import honey.road.node.persistence.HandlingStatsRepository;
import honey.road.node.persistence.PolicyRepository;
import honey.road.node.persistence.TicketRepository;
import honey.road.node.persistence.WindowRepository;
import honey.road.node.queue.domain.QueueSimulation;
import honey.road.node.queue.domain.Ticket;
import honey.road.node.queue.domain.TicketStatus;
import honey.road.node.queue.domain.WindowStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Позиция и ожидаемое время вызова [MVP; раздел 6.8].
 * <ul>
 *   <li>Позиция — число ожидающих талонов перед данным в его услуге (порядок EDF).</li>
 *   <li>ETA — момент, когда {@link QueueSimulation} назначит талон открытому окну. Симуляция учитывает
 *       допуск ({@code eligible_from}), адресные окна, оставшееся время текущих обслуживаний и общие услуги окон.</li>
 *   <li>Прогноз длительности: при {@code uniform} — среднее по услуге из {@code handling_stats}; при {@code heuristic} —
 *       каскад со скоростью сотрудника окна (16.2). Пока данных мало — значение из конфигурации.</li>
 *   <li>Нет открытого окна для талона — ETA не определено, {@code confidence = none} (S2).</li>
 * </ul>
 * Интерфейс под {@code HeuristicScoringStrategy} не меняется: подменяется только источник {@code durationSec}.
 */
@Component
public class WaitTimeEstimator {
    private final TicketRepository tickets;
    private final WindowRepository windows;
    private final HandlingStatsRepository stats;
    private final PolicyRepository policies;
    private final NodeProperties node;
    private final Clock clock;

    public WaitTimeEstimator(TicketRepository tickets, WindowRepository windows, HandlingStatsRepository stats,
                             PolicyRepository policies, NodeProperties node, Clock clock) {
        this.tickets = tickets; this.windows = windows; this.stats = stats; this.policies = policies;
        this.node = node; this.clock = clock;
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
        var all = estimateAll();
        var e = all.get(t.id());
        return e != null ? e : new Estimate(tickets.aheadOf(t), null, "none");
    }

    /** Оценки всех ожидающих талонов отделения одной симуляцией (для уведомлений и списков). */
    public Map<UUID, Estimate> estimateAll() {
        var branch = node.branchId();
        var now = clock.instant();
        var policy = policies.current();
        var waiting = tickets.waiting(branch);

        var durations = new HashMap<UUID, Long>();
        var measured = new HashSet<UUID>();
        java.util.function.ToLongFunction<UUID> byService = service -> durations.computeIfAbsent(service, s -> {
            var avg = stats.averageForService(s, policy.heuristicMinSamples());
            if (avg.isPresent()) { measured.add(s); }
            return Math.round(avg.orElse((double) policy.defaultServiceDurationSec()));
        });
        // При heuristic прогноз — тот же каскад, что и у score, с учётом сотрудника окна (разделы 6.8, 16.3);
        // при uniform — среднее по услуге.
        boolean perEmployee = "heuristic".equals(policy.scoringStrategy());
        var cascade = new HashMap<String, Long>();
        java.util.function.BiFunction<UUID, Ticket, Long> forWindow = (employee, t) -> {
            if (!perEmployee) { return byService.applyAsLong(t.serviceId()); }
            var key = t.serviceId() + "|" + employee + "|" + t.clientCategory();
            return cascade.computeIfAbsent(key, k -> Math.round(
                    stats.forecast(t.serviceId(), employee, t.clientCategory(), policy.heuristicMinSamples())
                            .map(HandlingStatsRepository.Forecast::seconds)
                            .orElse((double) policy.defaultServiceDurationSec())));
        };

        var servicesByWindow = windows.serviceIdsByWindow(branch);
        var activeByWindow = new HashMap<UUID, Ticket>();
        for (var a : tickets.byStatuses(branch, List.of("called", "serving"))) {
            if (a.windowId() != null) { activeByWindow.put(a.windowId(), a); }
        }
        var employeeByWindow = new HashMap<UUID, UUID>();
        var states = new java.util.ArrayList<QueueSimulation.WindowState>();
        for (var w : windows.all(branch)) {
            if (!WindowStatus.OPEN.equals(w.status())) { continue; }   // closing/closed новых не принимают
            employeeByWindow.put(w.id(), w.currentEmployeeId());
            var busy = activeByWindow.get(w.id());
            Instant freeAt = now;
            if (busy != null) {
                var started = busy.servingStartedAt() != null ? busy.servingStartedAt() : busy.calledAt();
                if (started != null) {
                    var employee = busy.employeeId() != null ? busy.employeeId() : w.currentEmployeeId();
                    freeAt = started.plusSeconds(forWindow.apply(employee, busy));
                }
            }
            states.add(new QueueSimulation.WindowState(w.id(), new HashSet<>(servicesByWindow.getOrDefault(w.id(), List.of())), freeAt));
        }

        var result = QueueSimulation.run(now, waiting, states,
                (window, t) -> forWindow.apply(employeeByWindow.get(window.windowId()), t),
                Duration.ofMinutes(policy.slackMin()));

        var position = new HashMap<UUID, Integer>();
        var counters = new HashMap<UUID, Integer>();
        for (var t : waiting) {
            position.put(t.id(), counters.merge(t.serviceId(), 1, Integer::sum) - 1);
        }
        var out = new HashMap<UUID, Estimate>();
        for (var t : waiting) {
            var eta = result.eta().get(t.id());
            if (eta == null) {
                out.put(t.id(), new Estimate(position.get(t.id()), null, "none"));
            } else {
                byService.applyAsLong(t.serviceId());                     // заполняет measured
                out.put(t.id(), new Estimate(position.get(t.id()), eta, measured.contains(t.serviceId()) ? "medium" : "low"));
            }
        }
        return out;
    }
}
