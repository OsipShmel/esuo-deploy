package honey.road.queue.application;

import honey.road.TicketView;
import honey.road.config.NodeProperties;
import honey.road.persistence.*;
import honey.road.queue.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import honey.road.persistence.CatalogRepository;
import honey.road.persistence.PolicyRepository;
import honey.road.persistence.SlotRepository;
import honey.road.persistence.TicketRepository;
import honey.road.queue.domain.DomainException;
import honey.road.queue.domain.PostalService;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class TicketQueries {
    private final CatalogRepository catalog;
    private final PolicyRepository policies;
    private final TicketRepository tickets;
    private final SlotRepository slots;
    private final honey.road.queue.application.TicketViews views;
    private final NodeProperties node;
    private final Clock clock;
    public TicketQueries(CatalogRepository catalog, PolicyRepository policies, TicketRepository tickets,
                         SlotRepository slots, honey.road.queue.application.TicketViews views, NodeProperties node, Clock clock) {
        this.catalog=catalog;this.policies=policies;this.tickets=tickets;this.slots=slots;this.views=views;this.node=node;this.clock=clock;
    }
    public TicketView get(UUID token) { return views.of(tickets.byToken(node.branchId(),token,false)); }

    public Map<String,Object> branch() {
        var branch=catalog.branch(node.branchId());
        return Map.of("id",branch.id(),"name",branch.name(),"timezone",branch.timezone(),
                "services",catalog.services(node.branchId()).stream().filter(PostalService::active).toList(),
                "categories",new TreeSet<>(policies.current().targetWaitMin().get("qr").keySet()));
    }

    public List<SlotView> slots(UUID serviceId, LocalDate date) {
        var service=catalog.service(node.branchId(),serviceId);
        if (!service.active()) { throw new DomainException("SERVICE_NOT_FOUND","Услуга недоступна"); }
        var policy=policies.current();
        var zone=ZoneId.of(catalog.branch(node.branchId()).timezone());
        var now=clock.instant();
        var today=now.atZone(zone).toLocalDate();
        if (date.isBefore(today) || date.isAfter(today.plusDays(policy.bookingHorizonDays()))) {
            throw DomainException.validation("Дата за пределами периода записи");
        }
        var result=new ArrayList<SlotView>();
        var close=date.atTime(policy.closingTime()).atZone(zone).toInstant();
        for (var start=date.atTime(policy.openingTime()).atZone(zone).toInstant();
             !start.plus(service.slotDurationMin(),ChronoUnit.MINUTES).isAfter(close);
             start=start.plus(service.slotDurationMin(),ChronoUnit.MINUTES)) {
            if (start.isAfter(now)) {
                long remaining=Math.max(0,service.slotCapacity()-slots.occupancy(serviceId,start));
                if (remaining>0) { result.add(new SlotView(start,start.plus(service.slotDurationMin(),ChronoUnit.MINUTES),remaining)); }
            }
        }
        return List.copyOf(result);
    }
    public record SlotView(Instant slotStart, Instant slotEnd, long remainingCapacity) {}
}
