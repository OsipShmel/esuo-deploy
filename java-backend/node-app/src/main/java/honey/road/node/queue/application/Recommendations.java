package honey.road.node.queue.application;

import honey.road.node.config.NodeProperties;
import honey.road.node.persistence.CatalogRepository;
import honey.road.node.persistence.PolicyRepository;
import honey.road.node.persistence.TicketRepository;
import honey.road.node.persistence.WindowRepository;
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
 * Рекомендация открыть окно (раздел 6.8) [MVP-демо]. Услуга попадает в рекомендацию, если выполнено хотя бы одно:
 * <ul>
 *   <li>{@code eta_overrun}: ETA хотя бы одного её талона позже дедлайна больше чем на {@code recommend-overdue-min};</li>
 *   <li>{@code overdue_count}: просроченных талонов услуги больше {@code recommend-overdue-count};</li>
 *   <li>{@code no_open_window}: есть ожидающие, а открытого окна с этой услугой нет (S2).</li>
 * </ul>
 * В ответе — услуги, где растёт очередь, причины и закрытые окна, которые могли бы её обслужить.
 * Решение об открытии принимает руководитель; система окно сама не открывает.
 */
@Service
public class Recommendations {
    public static final String REASON_ETA_OVERRUN = "eta_overrun";
    public static final String REASON_OVERDUE_COUNT = "overdue_count";
    public static final String REASON_NO_OPEN_WINDOW = "no_open_window";

    public record SuggestedWindow(UUID windowId, String name) {
    }

    public record ServiceRecommendation(UUID serviceId, String serviceName, int waiting, int overdue,
                                        long maxEtaOverrunSec, int openWindows, List<String> reasons,
                                        List<SuggestedWindow> suggestedWindows) {
    }

    public record Result(Instant generatedAt, boolean recommendOpenWindow, int overdueMin, int overdueCount,
                         List<ServiceRecommendation> services) {
    }

    private final WaitTimeEstimator estimator;
    private final TicketRepository tickets;
    private final WindowRepository windows;
    private final CatalogRepository catalog;
    private final PolicyRepository policies;
    private final NodeProperties node;
    private final Clock clock;

    public Recommendations(WaitTimeEstimator estimator, TicketRepository tickets, WindowRepository windows,
                           CatalogRepository catalog, PolicyRepository policies, NodeProperties node, Clock clock) {
        this.estimator = estimator; this.tickets = tickets; this.windows = windows; this.catalog = catalog;
        this.policies = policies; this.node = node; this.clock = clock;
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
        return new Result(now, !result.isEmpty(), policy.recommendOverdueMin(), policy.recommendOverdueCount(), List.copyOf(result));
    }
}
