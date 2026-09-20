package honey.road.node.queue.application;

import honey.road.contracts.dto.WindowView;
import honey.road.contracts.event.Actor;
import honey.road.contracts.event.EventTypes;
import honey.road.node.config.NodeProperties;
import honey.road.node.persistence.CatalogRepository;
import honey.road.node.persistence.EventRepository;
import honey.road.node.persistence.TicketRepository;
import honey.road.node.persistence.WindowRepository;
import honey.road.node.queue.domain.DomainException;
import honey.road.node.queue.domain.ServiceWindow;
import honey.road.node.queue.domain.TicketChange;
import honey.road.node.queue.domain.TicketStatus;
import honey.road.node.queue.domain.WindowStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Window open/close (sect 7, S1, S4).
 * <ul>
 *   <li>{@code graceful}: window → {@code closing}, calls nobody new, closes after {@code complete}.</li>
 *   <li>{@code force}: the active ticket goes back to {@code waiting} with its {@code due_at}, i.e. to the head
 *       of its queue; the window closes immediately.</li>
 *   <li>Waiting tickets targeted at the window lose the target ({@code target_window_id = null}).</li>
 * </ul>
 */
@Service
public class WindowCommands {
    public static final String MODE_GRACEFUL = "graceful";
    public static final String MODE_FORCE = "force";

    private final WindowRepository windows;
    private final TicketRepository tickets;
    private final CatalogRepository catalog;
    private final EventRepository events;
    private final WindowViews views;
    private final CallNext callNext;
    private final NodeProperties node;
    private final Clock clock;

    public WindowCommands(WindowRepository windows, TicketRepository tickets, CatalogRepository catalog,
                          EventRepository events, WindowViews views, CallNext callNext, NodeProperties node, Clock clock) {
        this.windows = windows; this.tickets = tickets; this.catalog = catalog; this.events = events;
        this.views = views; this.callNext = callNext; this.node = node; this.clock = clock;
    }

    @Transactional
    public WindowView open(UUID windowId, List<UUID> serviceIds, UUID employeeId) {
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (serviceIds == null || serviceIds.isEmpty()) {
            throw DomainException.validation("Укажите хотя бы одну услугу окна");
        }
        var services = new LinkedHashSet<>(serviceIds);
        for (var s : services) {
            if (!catalog.service(node.branchId(), s).active()) {
                throw new DomainException("SERVICE_NOT_FOUND", "Услуга недоступна");
            }
        }
        var window = windows.lock(node.branchId(), windowId);
        if (WindowStatus.CLOSING.equals(window.status())) {
            throw new DomainException("INVALID_STATE_TRANSITION", "Окно закрывается: сначала завершите текущего клиента");
        }
        boolean wasOpen = WindowStatus.OPEN.equals(window.status());
        windows.replaceServices(windowId, services);
        var opened = windows.update(window, WindowStatus.OPEN, employeeId != null ? employeeId : window.currentEmployeeId(),
                wasOpen ? null : now);
        events.windowEvent(opened, EventTypes.WINDOW_OPENED, actor(employeeId), now,
                Map.of("service_ids", List.copyOf(services), "reopened", wasOpen));
        callNext.autoCallIfEnabled(windowId, opened.currentEmployeeId());
        return views.of(opened);
    }

    @Transactional
    public WindowView close(UUID windowId, String mode, UUID employeeId) {
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        var effectiveMode = mode == null ? MODE_GRACEFUL : mode;
        if (!MODE_GRACEFUL.equals(effectiveMode) && !MODE_FORCE.equals(effectiveMode)) {
            throw DomainException.validation("mode: graceful | force");
        }
        var window = windows.lock(node.branchId(), windowId);
        if (WindowStatus.CLOSED.equals(window.status())) {
            return views.of(window);                                     // idempotent
        }
        var active = tickets.activeForWindow(windowId, true);
        if (active.isEmpty()) {
            return views.of(finishClose(window, actor(employeeId), now, "no_active_ticket"));
        }
        if (MODE_GRACEFUL.equals(effectiveMode)) {
            if (WindowStatus.CLOSING.equals(window.status())) {
                return views.of(window);
            }
            var closing = windows.update(window, WindowStatus.CLOSING, window.currentEmployeeId(), null);
            events.windowEvent(closing, EventTypes.WINDOW_CLOSING, actor(employeeId), now,
                    Map.of("active_ticket_id", active.get().id()));
            return views.of(closing);
        }
        var ticket = active.get();
        var requeued = tickets.apply(TicketChange.of(ticket).backToWaiting(now, ticket.dueAt()), now);
        events.ticketEvent(requeued, EventTypes.TICKET_REQUEUED, ticket.status(), actor(employeeId),
                Map.of("reason", "window_closed_force", "mode", "keep_position", "from_window_id", windowId));
        return views.of(finishClose(window, actor(employeeId), now, "force"));
    }

    /** Closes a window without an active ticket; also called from {@code complete} for a {@code closing} window. */
    @Transactional
    public ServiceWindow finishClose(ServiceWindow window, Actor actor, Instant now, String reason) {
        for (var t : tickets.lockTargetedTo(window.id())) {
            var released = tickets.apply(TicketChange.of(t).targetWindowId(null), now);
            events.ticketEvent(released, EventTypes.TICKET_REDIRECTED, TicketStatus.waiting, actor,
                    Map.of("reason", "target_window_closed", "from_target_window_id", window.id()));
        }
        var closed = windows.update(window, WindowStatus.CLOSED, null, null);
        events.windowEvent(closed, EventTypes.WINDOW_CLOSED, actor, now, Map.of("reason", reason));
        return closed;
    }

    static Actor actor(UUID employeeId) {
        return Actor.operator(employeeId == null ? null : employeeId.toString());
    }
}
