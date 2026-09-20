package honey.road.node.queue.application;

import honey.road.contracts.dto.TicketView;
import honey.road.node.config.NodeProperties;
import honey.road.node.persistence.TicketRepository;
import honey.road.node.queue.domain.TicketSource;
import honey.road.node.queue.domain.TicketStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Walk-in (sect 4.4): the operator issues a ticket and, with {@code call_now}, assigns it to this window right away.
 * A repeat with the same {@code Idempotency-Key} returns the current ticket state without a second call.
 */
@Service
public class Walkin {
    private final IssueTicket issue;
    private final CallNext callNext;
    private final TicketRepository tickets;
    private final TicketViews views;
    private final NodeProperties node;

    public Walkin(IssueTicket issue, CallNext callNext, TicketRepository tickets, TicketViews views, NodeProperties node) {
        this.issue = issue; this.callNext = callNext; this.tickets = tickets; this.views = views; this.node = node;
    }

    @Transactional
    public TicketView execute(UUID windowId, UUID serviceId, String category, boolean callNow, UUID employeeId, String key) {
        var issued = issue.execute(new IssueTicketCommand(TicketSource.walkin, serviceId, null, category, null, windowId), key);
        if (!callNow) {
            return issued;
        }
        var current = tickets.byId(node.branchId(), issued.ticketId(), false);
        if (current.status() != TicketStatus.waiting) {
            return views.of(current);
        }
        return callNext.callTicket(windowId, issued.ticketId(), employeeId);
    }
}
