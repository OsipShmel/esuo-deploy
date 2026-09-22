package honey.road.node.queue.application;

import honey.road.contracts.dto.TicketView;
import honey.road.node.config.NodeProperties;
import honey.road.node.persistence.*;
import honey.road.node.planning.BookingQuota;
import honey.road.node.planning.LoadLevel;
import honey.road.node.planning.LoadPlanner;
import honey.road.node.planning.PlanningSource;
import honey.road.node.queue.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import honey.road.node.persistence.CatalogRepository;
import honey.road.node.persistence.PolicyRepository;
import honey.road.node.persistence.SlotRepository;
import honey.road.node.persistence.TicketRepository;
import honey.road.node.queue.domain.DomainException;
import honey.road.node.queue.domain.PostalService;

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
    private final TicketViews views;
    private final NodeProperties node;
    private final Clock clock;
    private final LoadPlanner load;
    private final PlanningSource planning;
    public TicketQueries(CatalogRepository catalog, PolicyRepository policies, TicketRepository tickets,
                         SlotRepository slots, TicketViews views, NodeProperties node, Clock clock,
                         LoadPlanner load, PlanningSource planning) {
        this.catalog=catalog;this.policies=policies;this.tickets=tickets;this.slots=slots;this.views=views;
        this.node=node;this.clock=clock;this.load=load;this.planning=planning;
    }
    /** For the center (RPC {@code ticket.by_mp}): ticket by MP booking; the token leaves only in the RPC reply. */
    public TicketView byMpAppointment(String mpAppointmentId) {
        return views.of(tickets.byMpAppointmentId(node.branchId(), mpAppointmentId));
    }

    public TicketView get(UUID token) { return views.of(tickets.byToken(node.branchId(),token,false)); }

    /**
     * {@code load} appears only from {@code warn_walkin_from} up (load-planning.md 5, lever 4): honest waiting
     * for a client in the hall, not a number shown at every level.
     */
    public Map<String,Object> branch() {
        var branch=catalog.branch(node.branchId());
        var out=new LinkedHashMap<String,Object>();
        out.put("id",branch.id());
        out.put("name",branch.name());
        out.put("timezone",branch.timezone());
        out.put("services",catalog.services(node.branchId()).stream().filter(PostalService::active).toList());
        out.put("categories",new TreeSet<>(policies.current().targetWaitMin().get("qr").keySet()));
        var snapshot=load.snapshot();
        if (LoadLevel.atLeast(snapshot.level(),planning.current().levers().warnWalkinFrom())) {
            // clear_time_min пуст без открытого окна (LoadPlanner.measure): в это честнее не давать числа,
            // чем подставить 0 минут ожидания там, где никто клиента вообще не обслужит.
            var loadOut=new LinkedHashMap<String,Object>();
            loadOut.put("level",snapshot.level());
            loadOut.put("expected_wait_min",snapshot.clearTimeMin());
            out.put("load",loadOut);
        }
        return out;
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
        // Квота считается на час: один и тот же ответ для всех слотов часа, кешируем по часу (sect 5.1).
        var hourBlocked=new HashMap<Integer,Boolean>();
        for (var start=date.atTime(policy.openingTime()).atZone(zone).toInstant();
             !start.plus(service.slotDurationMin(),ChronoUnit.MINUTES).isAfter(close);
             start=start.plus(service.slotDurationMin(),ChronoUnit.MINUTES)) {
            if (start.isAfter(now)) {
                int hour=start.atZone(zone).getHour();
                if (hourBlocked.computeIfAbsent(hour,h -> quotaExhausted(service,date,h,zone))) { continue; }
                long remaining=Math.max(0,service.slotCapacity()-slots.occupancy(serviceId,start));
                if (remaining>0) { result.add(new SlotView(start,start.plus(service.slotDurationMin(),ChronoUnit.MINUTES),remaining)); }
            }
        }
        return List.copyOf(result);
    }

    private boolean quotaExhausted(PostalService service, LocalDate date, int hour, ZoneId zone) {
        double quota=load.quotaFor(date,hour);
        if (quota>=1.0) { return false; }
        var hourStart=date.atStartOfDay(zone).plusHours(hour).toInstant();
        long allowed=BookingQuota.allowed(service.slotDurationMin(),service.slotCapacity(),quota);
        return slots.occupancyForHour(service.id(),hourStart) >= allowed;
    }

    /** {@code GET /public/booking-limits?date=} (sect 5.1): only hours with a quota below 1 — the rest need no explaining. */
    public List<BookingLimitView> bookingLimits(LocalDate date) {
        return load.bookingQuota(date).stream()
                .filter(h -> h.quota()<1.0)
                .map(h -> new BookingLimitView(h.hourStart(),h.level(),h.quota()))
                .toList();
    }

    public record SlotView(Instant slotStart, Instant slotEnd, long remainingCapacity) {}
    public record BookingLimitView(Instant hourStart, String level, double quota) {}
}
