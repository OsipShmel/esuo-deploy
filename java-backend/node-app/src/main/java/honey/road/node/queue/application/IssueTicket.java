package honey.road.node.queue.application;

import honey.road.contracts.event.Actor;
import honey.road.contracts.event.EventTypes;
import honey.road.contracts.dto.TicketView;
import honey.road.node.config.NodeProperties;
import honey.road.node.persistence.*;
import honey.road.node.planning.BookingQuota;
import honey.road.node.planning.LoadPlanner;
import honey.road.node.queue.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import honey.road.node.config.JsonCodec;
import honey.road.node.persistence.*;
import honey.road.node.persistence.CatalogRepository;
import honey.road.node.persistence.EventRepository;
import honey.road.node.persistence.IdempotencyRepository;
import honey.road.node.persistence.PolicyRepository;
import honey.road.node.persistence.SlotRepository;
import honey.road.node.persistence.TicketRepository;
import honey.road.node.queue.domain.*;
import honey.road.node.queue.domain.DomainException;
import honey.road.node.queue.domain.PostalService;
import honey.road.node.queue.domain.QueuePolicy;
import honey.road.node.queue.domain.Ticket;
import honey.road.node.queue.domain.TicketSource;
import honey.road.node.queue.domain.TicketStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class IssueTicket {
    private final CatalogRepository catalog;
    private final PolicyRepository policies;
    private final IdempotencyRepository keys;
    private final SlotRepository slots;
    private final TicketRepository tickets;
    private final EventRepository events;
    private final TicketViews views;
    private final UuidGenerator ids;
    private final NodeProperties node;
    private final Clock clock;
    private final JsonCodec json;
    private final LoadPlanner load;

    public IssueTicket(CatalogRepository catalog, PolicyRepository policies, IdempotencyRepository keys,
                       SlotRepository slots, TicketRepository tickets, EventRepository events, TicketViews views,
                       UuidGenerator ids, NodeProperties node, Clock clock, JsonCodec json, LoadPlanner load) {
        this.catalog=catalog; this.policies=policies; this.keys=keys; this.slots=slots; this.tickets=tickets;
        this.events=events; this.views=views; this.ids=ids; this.node=node; this.clock=clock; this.json=json;
        this.load=load;
    }

    @Transactional
    public TicketView execute(IssueTicketCommand command, String idempotencyKey) {
        return issue(command,idempotencyKey,null);
    }

    @Transactional
    public TicketView fromMobileAppointment(String externalId, UUID service, Instant slot, String category) {
        // Internal entry for the MP adapter; public REST does not accept an external id.
        requireKey(externalId);
        return issue(new IssueTicketCommand(TicketSource.appointment,service,slot,category,null,null),externalId,externalId);
    }

    private TicketView issue(IssueTicketCommand original, String key, String externalId) {
        var command = normalized(original);
        requireKey(key);
        var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        String scope=node.branchId()+":"+(externalId != null ? "mp.appointment" : command.source().name())
                +(command.windowId() == null ? "" : ":"+command.windowId());
        String hash=hash(json.writeValueAsString(command));
        var replay=keys.reserve(scope,key,hash,now);
        if (replay.isPresent()) { return replay.get(); }

        var service=catalog.service(node.branchId(),command.serviceId());
        if (!service.active()) { throw new DomainException("SERVICE_NOT_FOUND","Услуга недоступна"); }
        var policy=policies.current();
        int wait=policy.targetWait(command.source(),command.clientCategory());
        var zone=ZoneId.of(catalog.branch(node.branchId()).timezone());
        Instant slotEnd=null;
        Actor actor=externalId == null ? Actor.client() : Actor.center();
        if (command.source() == TicketSource.appointment) {
            validateSlot(command.slotStart(),service,policy,zone,now);
            slots.lock(service.id(),command.slotStart());
            // Квота записи на пиковые часы (load-planning.md 5.1): уже принятые записи не отменяются никогда,
            // новая запись на исчерпанный час отклоняется — тем же кодом на вебе и на пути из МП.
            var slotDate=command.slotStart().atZone(zone).toLocalDate();
            int slotHour=command.slotStart().atZone(zone).getHour();
            double quota=load.quotaFor(slotDate,slotHour);
            if (quota<1.0) {
                var hourStart=slotDate.atStartOfDay(zone).plusHours(slotHour).toInstant();
                long allowed=BookingQuota.allowed(service.slotDurationMin(),service.slotCapacity(),quota);
                if (slots.occupancyForHour(service.id(),hourStart) >= allowed) {
                    throw new DomainException("BOOKING_LIMITED","Запись на это время закрыта из-за высокой нагрузки");
                }
            }
            if (slots.occupancy(service.id(),command.slotStart()) >= service.slotCapacity()) {
                throw new DomainException("SLOT_UNAVAILABLE","На выбранное время нет свободных мест");
            }
            slotEnd=command.slotStart().plus(service.slotDurationMin(),ChronoUnit.MINUTES);
        } else if (command.source() == TicketSource.qr) {
            tickets.lockDevice(node.branchId(),command.deviceId());
        } else {
            var window=catalog.lockWindow(node.branchId(),command.windowId());
            if (!"open".equals(window.status())) { throw new DomainException("WINDOW_NOT_OPEN","Окно закрыто"); }
            if (!catalog.supports(window.id(),service.id())) { throw new DomainException("SERVICE_NOT_IN_WINDOW","Услуга не оказывается в этом окне"); }
            var employee=catalog.employee(node.branchId(),window.currentEmployeeId());
            if (!employee.active()) { throw new DomainException("FORBIDDEN","Сотрудник неактивен"); }
            actor=Actor.operator(employee.id().toString());
        }
        boolean booked=command.source()==TicketSource.appointment;
        LocalDate day=now.atZone(zone).toLocalDate();
        String number=tickets.nextNumber(node.branchId(),service,day);
        var ticket=tickets.insert(Ticket.fresh(ids.next(now),UUID.randomUUID(),node.branchId(),service.id(),command.source(),
                booked?TicketStatus.booked: TicketStatus.waiting,command.clientCategory(),policy.configVersion(),number,now,day,
                command.slotStart(),slotEnd,booked?null:now,booked?null:now.plus(wait,ChronoUnit.MINUTES),
                command.deviceId(),externalId));
        var view=views.of(ticket);
        events.append(ticket,EventTypes.TICKET_CREATED,null,actor);
        keys.complete(scope,key,view);
        return view;
    }

    private static IssueTicketCommand normalized(IssueTicketCommand c) {
        if (c == null || c.source()==null || c.serviceId()==null) { throw DomainException.validation("Источник и услуга обязательны"); }
        String category=c.clientCategory()==null?"default":c.clientCategory().strip();
        if (category.isBlank() || category.length()>64) { throw DomainException.validation("Некорректная категория"); }
        if ((c.source()==TicketSource.appointment)!=(c.slotStart()!=null)) { throw DomainException.validation("Слот задаётся только для предварительной записи"); }
        if ((c.source()==TicketSource.walkin)!=(c.windowId()!=null)) { throw DomainException.validation("Окно задаётся только для живой очереди"); }
        if (c.source()==TicketSource.qr) {
            if (c.deviceId()==null || c.deviceId().isBlank() || c.deviceId().length()>128) { throw DomainException.validation("Идентификатор устройства обязателен, до 128 символов"); }
        } else if (c.deviceId()!=null) { throw DomainException.validation("Идентификатор устройства задаётся только для QR"); }
        return new IssueTicketCommand(c.source(),c.serviceId(),c.slotStart(),category,c.deviceId(),c.windowId());
    }

    private static void requireKey(String key) {
        if (key==null || key.isBlank() || key.length()>128) { throw DomainException.validation("Ключ идемпотентности обязателен, до 128 символов"); }
    }

    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static void validateSlot(Instant start, PostalService service, QueuePolicy policy, ZoneId zone, Instant now) {
        if (!start.isAfter(now)) { throw new DomainException("SLOT_EXPIRED","Время записи уже прошло"); }
        var date=start.atZone(zone).toLocalDate();
        if (date.isAfter(now.atZone(zone).toLocalDate().plusDays(policy.bookingHorizonDays()))) {
            throw DomainException.validation("Дата за пределами периода записи");
        }
        var open=date.atTime(policy.openingTime()).atZone(zone).toInstant();
        var close=date.atTime(policy.closingTime()).atZone(zone).toInstant();
        var duration=Duration.between(open,start);
        if (start.isBefore(open) || start.plus(service.slotDurationMin(),ChronoUnit.MINUTES).isAfter(close)
                || start.getNano()!=0 || duration.getSeconds()%(service.slotDurationMin()*60L)!=0) {
            throw DomainException.validation("Время не совпадает с доступным слотом отделения");
        }
    }
}
