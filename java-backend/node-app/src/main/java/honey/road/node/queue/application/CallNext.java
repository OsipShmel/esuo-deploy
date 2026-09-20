package honey.road.node.queue.application;

import honey.road.contracts.dto.TicketView;
import honey.road.contracts.event.Actor;
import honey.road.contracts.event.EventTypes;
import honey.road.node.config.NodeProperties;
import honey.road.node.persistence.EventRepository;
import honey.road.node.persistence.PolicyRepository;
import honey.road.node.persistence.TicketRepository;
import honey.road.node.persistence.WindowRepository;
import honey.road.node.queue.domain.DomainException;
import honey.road.node.queue.domain.QueueSelection;
import honey.road.node.queue.domain.ServiceWindow;
import honey.road.node.queue.domain.Ticket;
import honey.road.node.queue.domain.TicketChange;
import honey.road.node.queue.domain.TicketStatus;
import honey.road.node.queue.domain.WindowStatus;
import honey.road.node.queue.scoring.ScoreResult;
import honey.road.node.queue.scoring.ScoringStrategies;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Вызов следующего клиента (раздел 6.4). Гарантии от двойного назначения:
 * блокировка строки окна; головы очередей {@code FOR UPDATE SKIP LOCKED}; UPDATE с проверкой версии;
 * уникальный индекс «один активный талон на окно» как последний рубеж.
 */
@Service
public class CallNext {
    private final WindowRepository windows;
    private final TicketRepository tickets;
    private final PolicyRepository policies;
    private final ScoringStrategies scoring;
    private final EventRepository events;
    private final TicketViews views;
    private final NodeProperties node;
    private final Clock clock;

    public CallNext(WindowRepository windows, TicketRepository tickets, PolicyRepository policies,
                    ScoringStrategies scoring, EventRepository events, TicketViews views, NodeProperties node, Clock clock) {
        this.windows = windows; this.tickets = tickets; this.policies = policies; this.scoring = scoring;
        this.events = events; this.views = views; this.node = node; this.clock = clock;
    }

    @Transactional
    public TicketView execute(UUID windowId, UUID employeeId) {
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        var window = lockFreeOpenWindow(windowId);
        var employee = employeeId != null ? employeeId : window.currentEmployeeId();
        return selectAndAssign(window, employee, now)
                .map(views::of)
                .orElseThrow(() -> new DomainException("QUEUE_EMPTY", "Нет клиентов, которых может вызвать это окно"));
    }

    /**
     * Автовызов при {@code auto_call_on_free} (6.4): после {@code open} и {@code complete}. Вызывается внутри
     * транзакции команды. Не бросает исключений на «нечего вызывать»: иначе транзакция команды стала бы
     * rollback-only. Окно не {@code open} или уже занятое пропускается молча.
     */
    @Transactional
    public void autoCallIfEnabled(UUID windowId, UUID employeeId) {
        if (!policies.current().autoCallOnFree()) {
            return;
        }
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        var window = windows.lock(node.branchId(), windowId);
        if (!WindowStatus.OPEN.equals(window.status()) || tickets.activeForWindow(windowId, false).isPresent()) {
            return;
        }
        var employee = employeeId != null ? employeeId : window.currentEmployeeId();
        selectAndAssign(window, employee, now);
    }

    private java.util.Optional<Ticket> selectAndAssign(ServiceWindow window, UUID employee, Instant now) {
        var windowId = window.id();
        var policy = policies.current();

        var heads = new ArrayList<Ticket>();
        for (var service : windows.serviceIds(windowId)) {
            tickets.lockHead(node.branchId(), service, windowId, now).ifPresent(heads::add);
        }
        if (heads.isEmpty()) {
            return java.util.Optional.empty();
        }
        var strategy = scoring.get(policy.scoringStrategy());
        // Оценки считаются один раз на голову и идут в журнал: решение можно переиграть (раздел 6.6).
        var results = new LinkedHashMap<UUID, ScoreResult>();
        for (var h : heads) {
            results.put(h.id(), strategy.score(h, window, employee));
        }
        var selection = QueueSelection.choose(heads, now, Duration.ofMinutes(policy.slackMin()),
                t -> results.get(t.id()).value());

        var candidates = new ArrayList<Map<String, Object>>();
        for (var h : heads) {
            var r = results.get(h.id());
            var c = new LinkedHashMap<String, Object>();
            c.put("ticket_id", h.id());
            c.put("display_number", h.displayNumber());
            c.put("service_id", h.serviceId());
            c.put("due_at", h.dueAt());
            c.put("score", r.value());
            c.put("predicted_duration_sec", r.predictedDuration().toSeconds());
            c.put("basis", r.basis());
            c.put("samples", r.samples());
            candidates.add(c);
        }
        var extra = new LinkedHashMap<String, Object>();
        extra.put("reason", selection.reason());
        extra.put("scoring_strategy", strategy.name());
        extra.put("model_version", results.get(selection.chosen().id()).modelVersion());
        extra.put("candidates", candidates);
        return java.util.Optional.of(assign(selection.chosen(), window, employee, now, extra));
    }

    /**
     * Назначение конкретного талона окну (walk-in с {@code call_now}). Талон должен быть {@code waiting}
     * и относиться к услуге окна.
     */
    @Transactional
    public TicketView callTicket(UUID windowId, UUID ticketId, UUID employeeId) {
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        var window = lockFreeOpenWindow(windowId);
        var ticket = tickets.byId(node.branchId(), ticketId, true);
        if (ticket.status() != TicketStatus.waiting) {
            throw new DomainException("INVALID_STATE_TRANSITION", "Талон не ожидает вызова");
        }
        if (!windows.serviceIds(windowId).contains(ticket.serviceId())) {
            throw new DomainException("SERVICE_NOT_IN_WINDOW", "Услуга не оказывается в этом окне");
        }
        var employee = employeeId != null ? employeeId : window.currentEmployeeId();
        return views.of(assign(ticket, window, employee, now, Map.of("reason", "call_now")));
    }

    private ServiceWindow lockFreeOpenWindow(UUID windowId) {
        var window = windows.lock(node.branchId(), windowId);
        if (!WindowStatus.OPEN.equals(window.status())) {
            throw new DomainException("WINDOW_NOT_OPEN", "Окно не открыто");
        }
        tickets.activeForWindow(windowId, false).ifPresent(t -> {
            throw new DomainException("WINDOW_BUSY", "В окне уже активен талон " + t.displayNumber());
        });
        return window;
    }

    private Ticket assign(Ticket ticket, ServiceWindow window, UUID employee, Instant now, Map<String, Object> extra) {
        var called = tickets.apply(TicketChange.of(ticket)
                .status(TicketStatus.called).windowId(window.id()).employeeId(employee)
                .calledAt(now).callCount(1).targetWindowId(null), now);
        var data = new LinkedHashMap<String, Object>(extra);
        data.put("window_name", window.name());
        events.ticketEvent(called, EventTypes.TICKET_CALLED, TicketStatus.waiting,
                Actor.operator(employee == null ? null : employee.toString()), data);
        return called;
    }

}
