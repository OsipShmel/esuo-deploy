package honey.road.queue.application;

import honey.road.EventEnvelope;
import honey.road.TicketView;
import honey.road.config.NodeProperties;
import honey.road.queue.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import honey.road.persistence.EventRepository;
import honey.road.persistence.SlotRepository;
import honey.road.persistence.TicketRepository;
import honey.road.queue.domain.DomainException;
import honey.road.queue.domain.TicketSource;
import honey.road.queue.domain.TicketStatus;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class CancelTicket {
    private final TicketRepository tickets;
    private final SlotRepository slots;
    private final honey.road.queue.application.TicketViews views;
    private final EventRepository events;
    private final NodeProperties node;
    private final Clock clock;
    public CancelTicket(TicketRepository tickets, SlotRepository slots, honey.road.queue.application.TicketViews views,
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
        events.append(cancelled,"ticket.cancelled",ticket.status().name(),new EventEnvelope.Actor("client",null));
        return view;
    }
}
