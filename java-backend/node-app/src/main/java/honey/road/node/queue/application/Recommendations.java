package honey.road.node.queue.application;

import honey.road.node.config.NodeProperties;
import honey.road.node.persistence.CatalogRepository;
import honey.road.node.persistence.PolicyRepository;
import honey.road.node.persistence.TicketRepository;
import honey.road.node.persistence.WindowRepository;
import honey.road.node.planning.LoadPlanner;
import honey.road.node.queue.domain.PostalService;
import honey.road.node.queue.domain.Ticket;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Window-opening recommendation (sect 6.8) [MVP-demo]. A service is included if any holds:
 * <ul>
 *   <li>{@code eta_overrun}: some ticket's ETA exceeds its deadline by more than {@code recommend-overdue-min};</li>
 *   <li>{@code overdue_count}: more than {@code recommend-overdue-count} overdue tickets;</li>
 *   <li>{@code no_open_window}: waiting tickets but no open window for the service (S2).</li>
 * </ul>
 * Returns the services, reasons and closed windows that could serve them. The admin decides; nothing opens automatically.
 */
@Service
public class Recommendations {
    public static final String REASON_ETA_OVERRUN = "eta_overrun";
    public static final String REASON_OVERDUE_COUNT = "overdue_count";
    public static final String REASON_NO_OPEN_WINDOW = "no_open_window";
    /**
     * Load planning says the hour needs more windows than are open (load-planning.md 5, lever 1).
     *
     * <p>Unlike the other three, this reason does not wait for the queue to go wrong: the other reasons fire on
     * damage already done — a deadline missed, tickets overdue — while this one fires on the forecast, before
     * the clients arrive. That is the whole value of planning, and also why it only ever recommends: a window
     * is a person, and the system does not staff shifts.
     */
    public static final String REASON_LOAD_FORECAST = "load_forecast";

    public record SuggestedWindow(UUID windowId, String name) {
    }

    public record ServiceRecommendation(UUID serviceId, String serviceName, int waiting, int overdue,
                                        long maxEtaOverrunSec, int openWindows, List<String> reasons,
                                        List<SuggestedWindow> suggestedWindows) {
    }

    /** Branch-level part of the advice: how many windows the current hour is short of (sect 5). */
    public record LoadAdvice(String level, int open, int planned, int needed, int shortOf) {
    }

    public record Result(Instant generatedAt, boolean recommendOpenWindow, int overdueMin, int overdueCount,
                         List<ServiceRecommendation> services, LoadAdvice load) {
    }

    private final WaitTimeEstimator estimator;
    private final TicketRepository tickets;
    private final WindowRepository windows;
    private final CatalogRepository catalog;
    private final PolicyRepository policies;
    private final LoadPlanner planner;
    private final NodeProperties node;
    private final Clock clock;

    public Recommendations(WaitTimeEstimator estimator, TicketRepository tickets, WindowRepository windows,
                           CatalogRepository catalog, PolicyRepository policies, LoadPlanner planner,
                           NodeProperties node, Clock clock) {
        this.estimator = estimator; this.tickets = tickets; this.windows = windows; this.catalog = catalog;
        this.policies = policies; this.planner = planner; this.node = node; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Result current() {
        var now = clock.instant();
        var policy = policies.current();
        var branch = node.branchId();
        var waiting = tickets.waiting(branch);
        var estimates = estimator.estimateAll();
        var services = catalog.services(branch).stream().collect(Collectors.toMap(PostalService::id, s -> s));
        var servicesByWindow = windows.serviceIdsByWindow(branch);
        var allWindows = windows.all(branch);

        var byService = new HashMap<UUID, List<Ticket>>();
        waiting.forEach(t -> byService.computeIfAbsent(t.serviceId(), k -> new ArrayList<>()).add(t));

        // Планирование нагрузки — отдельный источник совета: оно смотрит вперёд, а не на уже сломанное.
        var load = advice();
        var result = new ArrayList<ServiceRecommendation>();
        for (var entry : byService.entrySet()) {
            var serviceId = entry.getKey();
            var list = entry.getValue();
            int overdue = (int) list.stream().filter(t -> t.dueAt().isBefore(now)).count();
            long maxOverrun = 0;
            for (var t : list) {
                var e = estimates.get(t.id());
                if (e != null && e.eta() != null) {
                    maxOverrun = Math.max(maxOverrun, e.eta().getEpochSecond() - t.dueAt().getEpochSecond());
                }
            }
            int open = (int) allWindows.stream().filter(w -> "open".equals(w.status()))
                    .filter(w -> servicesByWindow.getOrDefault(w.id(), List.of()).contains(serviceId)).count();

            var reasons = new ArrayList<String>();
            if (maxOverrun > policy.recommendOverdueMin() * 60L) { reasons.add(REASON_ETA_OVERRUN); }
            if (overdue > policy.recommendOverdueCount()) { reasons.add(REASON_OVERDUE_COUNT); }
            if (open == 0) { reasons.add(REASON_NO_OPEN_WINDOW); }
            if (load != null && load.shortOf() > 0) { reasons.add(REASON_LOAD_FORECAST); }
            if (reasons.isEmpty()) { continue; }

            var suggested = allWindows.stream()
                    .filter(w -> "closed".equals(w.status()))
                    .filter(w -> servicesByWindow.getOrDefault(w.id(), List.of()).contains(serviceId))
                    .map(w -> new SuggestedWindow(w.id(), w.name())).toList();
            var service = services.get(serviceId);
            result.add(new ServiceRecommendation(serviceId, service == null ? "?" : service.name(), list.size(), overdue,
                    Math.max(0, maxOverrun), open, List.copyOf(reasons), suggested));
        }
        result.sort(Comparator.comparingLong(ServiceRecommendation::maxEtaOverrunSec).reversed()
                .thenComparing(ServiceRecommendation::serviceName));
        // Совет остаётся советом и тогда, когда очередь ещё цела: не хватает окон на этот час — уже повод.
        boolean recommend = !result.isEmpty() || (load != null && load.shortOf() > 0);
        return new Result(now, recommend, policy.recommendOverdueMin(), policy.recommendOverdueCount(),
                List.copyOf(result), load);
    }

    /**
     * Window shortage from the load planner, or {@code null} when planning is off.
     *
     * <p>Planning must never be able to break the recommendation the branch had before it existed, so a failure
     * here degrades to "no load advice" instead of an error on the admin screen.
     */
    private LoadAdvice advice() {
        try {
            var snapshot = planner.snapshot();
            var w = snapshot.windows();
            int wanted = Math.max(w.planned(), w.needed());
            return new LoadAdvice(snapshot.level(), w.open(), w.planned(), w.needed(), Math.max(0, wanted - w.open()));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
