package honey.road.node.queue.application;

import honey.road.contracts.event.Actor;
import honey.road.contracts.event.EventTypes;
import honey.road.contracts.dto.TicketView;
import honey.road.node.config.NodeProperties;
import honey.road.node.queue.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import honey.road.node.persistence.EventRepository;
import honey.road.node.persistence.SlotRepository;
import honey.road.node.persistence.TicketRepository;
import honey.road.node.queue.domain.DomainException;
import honey.road.node.queue.domain.TicketSource;
import honey.road.node.queue.domain.TicketStatus;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class CancelTicket {
    private final TicketRepository tickets;
    private final SlotRepository slots;
    private final honey.road.node.queue.application.TicketViews views;
    private final EventRepository events;
    private final NodeProperties node;
    private final Clock clock;
    public CancelTicket(TicketRepository tickets, SlotRepository slots, honey.road.node.queue.application.TicketViews views,
                        EventRepository events, NodeProperties node, Clock clock) {
        this.tickets=tickets;this.slots=slots; this.views=views;this.events=events;this.node=node;this.clock=clock;
    }

    @Transactional
    public TicketView execute(UUID token) {
        var snapshot=tickets.byToken(node.branchId(),token,false);
        // Порядок блокировок совпадает с бронированием: сначала слот, затем талон.
        if (snapshot.source()== TicketSource.appointment) { slots.lock(snapshot.serviceId(),snapshot.slotStart()); }
        Ticket ticket=tickets.byToken(node.branchId(),token,true);
        if (ticket.status()== TicketStatus.cancelled) { return views.of(ticket); }
        if (!ticket.status().canCancel()) { throw new DomainException("INVALID_STATE_TRANSITION","Этот талон уже нельзя отменить"); }
        Ticket cancelled=tickets.cancel(ticket,clock.instant().truncatedTo(ChronoUnit.MILLIS));
        var view=views.of(cancelled);
        events.append(cancelled,EventTypes.TICKET_CANCELLED,ticket.status().name(),Actor.client());
        return view;
    }
}
