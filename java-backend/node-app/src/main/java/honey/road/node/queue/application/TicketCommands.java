package honey.road.node.queue.application;

import honey.road.contracts.dto.TicketView;
import honey.road.contracts.event.Actor;
import honey.road.contracts.event.EventTypes;
import honey.road.node.config.NodeProperties;
import honey.road.node.persistence.CatalogRepository;
import honey.road.node.persistence.EventRepository;
import honey.road.node.persistence.HandlingStatsRepository;
import honey.road.node.persistence.PolicyRepository;
import honey.road.node.persistence.TicketRepository;
import honey.road.node.persistence.WindowRepository;
import honey.road.node.queue.domain.DomainException;
import honey.road.node.queue.domain.QueuePolicy;
import honey.road.node.queue.domain.ServiceWindow;
import honey.road.node.queue.domain.Ticket;
import honey.road.node.queue.domain.TicketChange;
import honey.road.node.queue.domain.TicketSource;
import honey.road.node.queue.domain.TicketStatus;
import honey.road.node.queue.domain.WindowStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Ticket commands (transition table 5.4). Lock order matches {@link CallNext}: the active ticket's window first,
 * then the ticket. {@code expectedVersion}, if given, must match or {@code STALE_STATE}.
 */
@Service
public class TicketCommands {
    private final TicketRepository tickets;
    private final WindowRepository windows;
    private final CatalogRepository catalog;
    private final PolicyRepository policies;
    private final EventRepository events;
    private final HandlingStatsRepository stats;
    private final WindowCommands windowCommands;
    private final CallNext callNext;
    private final TicketViews views;
    private final NodeProperties node;
    private final Clock clock;

    public TicketCommands(TicketRepository tickets, WindowRepository windows, CatalogRepository catalog,
                          PolicyRepository policies, EventRepository events, HandlingStatsRepository stats,
                          WindowCommands windowCommands, CallNext callNext, TicketViews views, NodeProperties node, Clock clock) {
        this.tickets = tickets; this.windows = windows; this.catalog = catalog; this.policies = policies;
        this.events = events; this.stats = stats; this.windowCommands = windowCommands; this.callNext = callNext; this.views = views;
        this.node = node; this.clock = clock;
    }

    // ------------------------------------------------------------------ operator

    @Transactional
    public TicketView start(UUID id, Integer expectedVersion, Actor actor) {
        var ctx = lock(id, expectedVersion, EnumSet.of(TicketStatus.called));
        var now = now();
        var serving = tickets.apply(TicketChange.of(ctx.ticket).status(TicketStatus.serving).servingStartedAt(now), now);
        events.ticketEvent(serving, EventTypes.TICKET_SERVING, ctx.ticket.status(), actor, Map.of());
        return views.of(serving);
    }

    @Transactional
    public TicketView complete(UUID id, Integer expectedVersion, Actor actor) {
        var ctx = lock(id, expectedVersion, EnumSet.of(TicketStatus.called, TicketStatus.serving));
        var now = now();
        var t = ctx.ticket;
        var done = tickets.apply(TicketChange.of(t).status(TicketStatus.done).completedAt(now).closeReason("completed"), now);
        var started = t.servingStartedAt() != null ? t.servingStartedAt() : t.calledAt();
        long seconds = started == null ? 0 : Duration.between(started, now).toSeconds();
        stats.record(t.serviceId(), t.employeeId(), t.clientCategory(), seconds, now);
        events.ticketEvent(done, EventTypes.TICKET_COMPLETED, t.status(), actor, Map.of("service_duration_sec", seconds));
        if (ctx.window != null && WindowStatus.CLOSING.equals(ctx.window.status())) {
            windowCommands.finishClose(ctx.window, actor, now, "graceful_after_complete");
        } else if (ctx.window != null) {
            callNext.autoCallIfEnabled(ctx.window.id(), t.employeeId());
        }
        return views.of(done);
    }

    /** Recall; after {@code max_recalls} → {@code no_show} (S5). */
    @Transactional
    public TicketView recall(UUID id, Integer expectedVersion, Actor actor) {
        var ctx = lock(id, expectedVersion, EnumSet.of(TicketStatus.called));
        return views.of(recallLocked(ctx.ticket, actor, now()));
    }

    /** For the scheduler: the ticket is already locked. */
    @Transactional
    public Ticket recallLocked(Ticket t, Actor actor, Instant now) {
        var policy = policies.current();
        if (t.callCount() > policy.maxRecalls()) {
            return noShowLocked(t, actor, now, "no_show_after_recalls");
        }
        var recalled = tickets.apply(TicketChange.of(t).callCount(t.callCount() + 1).calledAt(now), now);
        events.ticketEvent(recalled, EventTypes.TICKET_RECALLED, t.status(), actor, Map.of("call_count", recalled.callCount()));
        return recalled;
    }

    @Transactional
    public TicketView noShow(UUID id, Integer expectedVersion, Actor actor) {
        var ctx = lock(id, expectedVersion, EnumSet.of(TicketStatus.called));
        return views.of(noShowLocked(ctx.ticket, actor, now(), "operator"));
    }

    @Transactional
    public Ticket noShowLocked(Ticket t, Actor actor, Instant now, String reason) {
        var gone = tickets.apply(TicketChange.of(t).status(TicketStatus.no_show).closeReason(reason), now);
        events.ticketEvent(gone, EventTypes.TICKET_NO_SHOW, t.status(), actor, Map.of("reason", reason));
        return gone;
    }

    /** Requeue: {@code keep_position} keeps due_at (queue head), {@code to_tail} recomputes it. */
    @Transactional
    public TicketView requeue(UUID id, String mode, Integer expectedVersion, Actor actor) {
        var ctx = lock(id, expectedVersion, EnumSet.of(TicketStatus.called, TicketStatus.serving));
        var policy = policies.current();
        var effective = mode == null ? policy.requeueDefaultMode() : mode;
        if (!QueuePolicy.REQUEUE_KEEP.equals(effective) && !QueuePolicy.REQUEUE_TAIL.equals(effective)) {
            throw DomainException.validation("mode: keep_position | to_tail");
        }
        var now = now();
        var t = ctx.ticket;
        var due = QueuePolicy.REQUEUE_KEEP.equals(effective) ? t.dueAt()
                : now.plus(policy.targetWait(t.source(), t.clientCategory()), ChronoUnit.MINUTES);
        var requeued = tickets.apply(TicketChange.of(t).backToWaiting(now, due), now);
        events.ticketEvent(requeued, EventTypes.TICKET_REQUEUED, t.status(), actor,
                Map.of("mode", effective, "from_window_id", t.windowId()));
        return views.of(requeued);
    }

    /**
     * Redirect (S3, S4): change the service and/or target a window. {@code due_at} is kept: the client has already waited.
     */
    @Transactional
    public TicketView redirect(UUID id, UUID serviceId, UUID targetWindowId, String reason, Integer expectedVersion, Actor actor) {
        if (serviceId == null && targetWindowId == null) {
            throw DomainException.validation("Укажите service_id и/или window_id");
        }
        var ctx = lock(id, expectedVersion, EnumSet.of(TicketStatus.waiting, TicketStatus.called, TicketStatus.serving));
        var t = ctx.ticket;
        var newService = serviceId != null ? serviceId : t.serviceId();
        if (!catalog.service(node.branchId(), newService).active()) {
            throw new DomainException("SERVICE_NOT_FOUND", "Услуга недоступна");
        }
        if (targetWindowId != null) {
            var target = windows.get(node.branchId(), targetWindowId);
            if (!windows.serviceIds(target.id()).contains(newService)) {
                throw new DomainException("SERVICE_NOT_IN_WINDOW", "Окно не оказывает эту услугу");
            }
            if (target.id().equals(t.windowId())) {
                throw DomainException.validation("Талон уже в этом окне");
            }
        }
        var now = now();
        var change = TicketChange.of(t).serviceId(newService).targetWindowId(targetWindowId);
        if (t.active()) {
            change.backToWaiting(now, t.dueAt());
        }
        var redirected = tickets.apply(change, now);
        var data = new LinkedHashMap<String, Object>();
        data.put("reason", reason);
        data.put("from_service_id", t.serviceId());
        data.put("from_window_id", t.windowId());
        events.ticketEvent(redirected, EventTypes.TICKET_REDIRECTED, t.status(), actor, data);
        return views.of(redirected);
    }

    /** Booking check-in by the operator (by ticket id). */
    @Transactional
    public TicketView checkIn(UUID id, Integer expectedVersion, Actor actor) {
        var t = tickets.byId(node.branchId(), id, true);
        checkVersion(t, expectedVersion);
        return views.of(checkInLocked(t, actor));
    }

    // ------------------------------------------------------------------ client by token

    @Transactional
    public TicketView checkInByToken(UUID token) {
        var t = tickets.byToken(node.branchId(), token, true);
        return views.of(checkInLocked(t, Actor.client()));
    }

    @Transactional
    public TicketView postpone(UUID token, int minutes) {
        var t = tickets.byToken(node.branchId(), token, true);
        var policy = policies.current();
        if (t.status() != TicketStatus.waiting) {
            throw new DomainException("INVALID_STATE_TRANSITION", "Отложить можно только ожидающий талон");
        }
        if (minutes < 1 || minutes > policy.postponeMaxMin()) {
            throw DomainException.validation("Можно отложить на 1–" + policy.postponeMaxMin() + " мин");
        }
        if (t.postponeCount() >= policy.postponeMaxCount()) {
            throw new DomainException("INVALID_STATE_TRANSITION", "Лимит откладываний исчерпан");
        }
        var now = now();
        var postponed = tickets.apply(TicketChange.of(t)
                .eligibleFrom(now.plus(minutes, ChronoUnit.MINUTES)).postponeCount(t.postponeCount() + 1), now);
        events.ticketEvent(postponed, EventTypes.TICKET_POSTPONED, t.status(), Actor.client(), Map.of("minutes", minutes));
        return views.of(postponed);
    }

    // ------------------------------------------------------------------ internal

    /** Check-in (sect 6.3, S6, S7): eligible from slot_start − early, deadline slot_start; lateness per late_policy. */
    private Ticket checkInLocked(Ticket t, Actor actor) {
        if (t.status() == TicketStatus.waiting && t.source() == TicketSource.appointment && t.checkedInAt() != null) {
            return t;                                                       // repeated check-in is idempotent
        }
        if (t.status() != TicketStatus.booked) {
            throw new DomainException("INVALID_STATE_TRANSITION", "Check-in возможен только для записи");
        }
        var policy = policies.current();
        var now = now();
        var zone = ZoneId.of(catalog.branch(node.branchId()).timezone());
        if (!t.slotStart().atZone(zone).toLocalDate().equals(now.atZone(zone).toLocalDate())) {
            throw DomainException.validation("Check-in доступен только в день записи");
        }
        var retentionEnd = t.slotStart().plus(Math.max(policy.graceLateMin(), policy.lateCheckinRetentionMin()), ChronoUnit.MINUTES);
        if (now.isAfter(retentionEnd)) {
            throw new DomainException("SLOT_EXPIRED", "Время записи истекло");
        }
        boolean late = now.isAfter(t.slotStart().plus(policy.graceLateMin(), ChronoUnit.MINUTES));
        Instant eligible;
        Instant due;
        if (!late) {
            var earliest = t.slotStart().minus(policy.earlyCheckinMin(), ChronoUnit.MINUTES);
            eligible = now.isAfter(earliest) ? now : earliest;
            due = t.slotStart();
        } else if (QueuePolicy.LATE_REJECT.equals(policy.latePolicy())) {
            throw new DomainException("SLOT_EXPIRED", "Опоздание больше допустимого");
        } else {
            eligible = now;
            due = now.plus(policy.targetWait(TicketSource.qr, t.clientCategory()), ChronoUnit.MINUTES);
        }
        var checked = tickets.apply(TicketChange.of(t).status(TicketStatus.waiting)
                .checkedInAt(now).eligibleFrom(eligible).dueAt(due), now);
        events.ticketEvent(checked, EventTypes.TICKET_CHECKED_IN, t.status(), actor,
                Map.of("late", late, "late_policy", late ? policy.latePolicy() : "on_time"));
        return checked;
    }

    private record Locked(Ticket ticket, ServiceWindow window) {
    }

    private Locked lock(UUID id, Integer expectedVersion, Set<TicketStatus> allowed) {
        var snapshot = tickets.byId(node.branchId(), id, false);
        ServiceWindow window = snapshot.active() && snapshot.windowId() != null
                ? windows.lock(node.branchId(), snapshot.windowId()) : null;
        var t = tickets.byId(node.branchId(), id, true);
        if (window != null && !Objects.equals(t.windowId(), window.id())) {
            throw new DomainException("STALE_STATE", "Талон изменился, обновите данные");
        }
        checkVersion(t, expectedVersion);
        if (!allowed.contains(t.status())) {
            throw new DomainException("INVALID_STATE_TRANSITION",
                    "Недопустимо для талона в статусе " + t.status().name());
        }
        return new Locked(t, window);
    }

    private static void checkVersion(Ticket t, Integer expected) {
        if (expected != null && expected != t.version()) {
            throw new DomainException("STALE_STATE", "Талон изменился, обновите данные");
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
