package honey.road.node.queue.application;

import honey.road.contracts.dto.WindowView;
import honey.road.node.config.NodeProperties;
import honey.road.node.persistence.TicketRepository;
import honey.road.node.persistence.WindowRepository;
import honey.road.node.queue.domain.ServiceWindow;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class WindowViews {
    private final WindowRepository windows;
    private final TicketRepository tickets;
    private final TicketViews ticketViews;
    private final NodeProperties node;

    public WindowViews(WindowRepository windows, TicketRepository tickets, TicketViews ticketViews, NodeProperties node) {
        this.windows = windows; this.tickets = tickets; this.ticketViews = ticketViews; this.node = node;
    }

    public WindowView of(ServiceWindow w) {
        var active = tickets.activeForWindow(w.id(), false).map(ticketViews::of).orElse(null);
        return new WindowView(w.id(), w.name(), w.status(), w.currentEmployeeId(), windows.serviceIds(w.id()), active, w.version());
    }

    @Transactional(readOnly = true)
    public List<WindowView> all() {
        return windows.all(node.branchId()).stream().map(this::of).toList();
    }

    @Transactional(readOnly = true)
    public WindowView get(java.util.UUID id) {
        return of(windows.get(node.branchId(), id));
    }
}
