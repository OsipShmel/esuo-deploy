package honey.road.node.it;

import honey.road.contracts.event.Actor;
import honey.road.node.queue.application.IssueTicketCommand;
import honey.road.node.queue.domain.DomainException;
import honey.road.node.queue.domain.TicketSource;
import honey.road.node.scheduler.QueueTimers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Check-in записи и её истечение (разделы 5.4, 6.3, S6–S8): раньше времени, вовремя, с опозданием
 * (по {@code late_policy}), сверх срока хранения, повтор, истечение планировщиком.
 * <p>Запись создаётся на завтра штатным путём, затем её слот сдвигается в БД относительно «сейчас»: так время
 * не зависит от часов работы отделения. Тесты со сдвигом слота в прошлое не рассчитаны на запуск между 00:00 и
 * 01:30 по Москве: слот уйдёт во «вчера», и check-in ответит «только в день записи».
 * Настройки: early 15 мин, grace 10 мин, retention 60 мин, late_policy=convert_to_qr, цель ожидания QR 20 мин.
 */
class CheckInIT extends AbstractNodeIT {
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private final Actor operator = Actor.operator(E1.toString());

    @Autowired QueueTimers timers;

    private UUID booked(String key, int tomorrowHour, Instant slot) {
        var tomorrow = LocalDate.now(ZONE).plusDays(1).atTime(tomorrowHour, 0).atZone(ZONE).toInstant();
        var view = issue.execute(new IssueTicketCommand(TicketSource.appointment, PARCEL, tomorrow, "default", null, null), key);
        jdbc.update("UPDATE tickets SET slot_start=?, slot_end=? WHERE id=?", Timestamp.from(slot),
                Timestamp.from(slot.plus(15, ChronoUnit.MINUTES)), view.ticketId());
        return view.ticketId();
    }

    private Instant minutesFromNow(long minutes) {
        return Instant.now().plus(minutes, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
    }

    private Instant column(UUID ticket, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM tickets WHERE id=?", Timestamp.class, ticket).toInstant();
    }

    private static String code(Throwable e) {
        return ((DomainException) e).code();
    }

    private String payloadOf(UUID ticket) {
        return jdbc.queryForObject("SELECT payload::text FROM ticket_events WHERE ticket_id=? AND event_type='ticket.checked_in'",
                String.class, ticket);
    }

    // ------------------------------------------------------------------ S6: раньше времени

    @Test
    void earlyCheckInIsAllowedButTheTicketWaitsUntilTheEarlyWindowOpens() {
        var slot = minutesFromNow(30);
        var id = booked("early", 10, slot);

        var view = tickets.checkIn(id, null, operator);

        assertThat(view.status()).isEqualTo("waiting");
        assertThat(column(id, "due_at")).isEqualTo(slot);                                       // дедлайн = время слота
        assertThat(column(id, "eligible_from")).isEqualTo(slot.minus(15, ChronoUnit.MINUTES));   // допуск с slot − early
        // Раньше допуска запись не вызывается, чтобы не вытеснять тех, кто уже ждёт.
        openWindow(W1, E1, PARCEL);
        assertThatThrownBy(() -> callNext.execute(W1, null)).isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("QUEUE_EMPTY"));

        jdbc.update("UPDATE tickets SET eligible_from = now() - interval '1 minute' WHERE id=?", id);   // «время подошло»
        assertThat(callNext.execute(W1, null).ticketId()).isEqualTo(id);
    }

    @Test
    void onTimeCheckInInsideTheEarlyWindowIsCallableAtOnce() {
        var slot = minutesFromNow(5);
        var id = booked("ontime", 10, slot);
        tickets.checkIn(id, null, operator);

        assertThat(column(id, "due_at")).isEqualTo(slot);
        assertThat(column(id, "eligible_from")).isEqualTo(column(id, "checked_in_at"));         // допуск = момент check-in
        openWindow(W1, E1, PARCEL);
        assertThat(callNext.execute(W1, null).ticketId()).isEqualTo(id);
    }

    @Test
    void appointmentWithEarlierSlotBeatsAWalkInWhoArrivedEarlier() {
        openWindow(W1, E1, PARCEL);
        var walkin = qr(PARCEL, "queue-client");                                   // пришёл раньше, дедлайн +20 мин
        var slot = minutesFromNow(3);
        var appointment = booked("priority", 11, slot);
        tickets.checkIn(appointment, null, operator);                              // дедлайн = слот через 3 мин

        assertThat(callNext.execute(W1, null).ticketId()).isEqualTo(appointment);  // запись на время впереди
        assertThat(status(walkin)).isEqualTo("waiting");
    }

    // ------------------------------------------------------------------ S7: опоздание

    @Test
    void lateWithinGraceKeepsThePriorityOfTheSlot() {
        var slot = minutesFromNow(-5);                                             // опоздал на 5 мин, grace 10
        var id = booked("grace", 10, slot);
        tickets.checkIn(id, null, operator);

        assertThat(column(id, "due_at")).isEqualTo(slot);                          // дедлайн — по-прежнему слот
        assertThat(payloadOf(id)).contains("\"late\": false").contains("on_time");
    }

    @Test
    void lateBeyondGraceIsConvertedToQrDeadlineFromCheckIn() {
        var slot = minutesFromNow(-30);                                            // опоздал на 30 мин
        var id = booked("late", 10, slot);
        tickets.checkIn(id, null, operator);

        var checkedIn = column(id, "checked_in_at");
        assertThat(column(id, "due_at")).isEqualTo(checkedIn.plus(20, ChronoUnit.MINUTES));   // как QR-талон от check-in
        assertThat(column(id, "eligible_from")).isEqualTo(checkedIn);
        assertThat(payloadOf(id)).contains("\"late\": true").contains("convert_to_qr");
        // Клиент не теряет визит, но не обходит тех, кто пришёл вовремя.
        openWindow(W1, E1, PARCEL);
        var onTime = qr(PARCEL, "punctual");
        jdbc.update("UPDATE tickets SET due_at = now() + interval '10 minutes' WHERE id=?", onTime);
        assertThat(callNext.execute(W1, null).ticketId()).isEqualTo(onTime);
    }

    @Test
    void latePolicyRejectRefusesTheLateClientAndKeepsTheBooking() {
        jdbc.update("UPDATE branch_config SET central_config = jsonb_set(central_config, '{late_policy}', '\"reject\"') WHERE id=1");
        var id = booked("reject", 10, minutesFromNow(-30));

        assertThatThrownBy(() -> tickets.checkIn(id, null, operator)).isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("SLOT_EXPIRED"));
        assertThat(status(id)).isEqualTo("booked");
    }

    @Test
    void checkInAfterTheRetentionPeriodIsRefused() {
        var id = booked("expired", 10, minutesFromNow(-90));                       // retention 60 мин

        assertThatThrownBy(() -> tickets.checkIn(id, null, operator)).isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("SLOT_EXPIRED"));
        assertThat(status(id)).isEqualTo("booked");
    }

    // ------------------------------------------------------------------ прочие правила

    @Test
    void checkInIsOnlyAllowedOnTheDayOfTheAppointment() {
        var tomorrow = LocalDate.now(ZONE).plusDays(1).atTime(10, 0).atZone(ZONE).toInstant();
        var view = issue.execute(new IssueTicketCommand(TicketSource.appointment, PARCEL, tomorrow, "default", null, null), "tomorrow");

        assertThatThrownBy(() -> tickets.checkInByToken(view.token())).isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("VALIDATION_ERROR"));
        assertThat(status(view.ticketId())).isEqualTo("booked");
    }

    @Test
    void repeatedCheckInIsIdempotentAndOnlyBookingsCanCheckIn() {
        var id = booked("twice", 10, minutesFromNow(5));
        var first = tickets.checkIn(id, null, operator);
        var second = tickets.checkIn(id, null, operator);
        assertThat(second.version()).isEqualTo(first.version());                     // повтор ничего не меняет
        assertThat(count("SELECT count(*) FROM ticket_events WHERE ticket_id=? AND event_type='ticket.checked_in'", id)).isEqualTo(1);

        var walkinTicket = qr(PARCEL, "not-a-booking");
        assertThatThrownBy(() -> tickets.checkIn(walkinTicket, null, operator)).isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("INVALID_STATE_TRANSITION"));
    }

    @Test
    void clientCanCheckInByTokenAndStaleVersionIsRejectedForOperator() {
        var id = booked("bytoken", 10, minutesFromNow(5));
        var token = jdbc.queryForObject("SELECT public_token FROM tickets WHERE id=?", UUID.class, id);

        assertThat(tickets.checkInByToken(token).status()).isEqualTo("waiting");

        var other = booked("stale", 11, minutesFromNow(6));
        assertThatThrownBy(() -> tickets.checkIn(other, 99, operator)).isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("STALE_STATE"));
    }

    // ------------------------------------------------------------------ S8: истечение записи

    @Test
    void schedulerExpiresOnlyBookingsPastTheRetentionPeriod() {
        var gone = booked("gone", 10, minutesFromNow(-90));                        // не пришёл, срок хранения вышел
        var recent = booked("recent", 11, minutesFromNow(-30));                    // опоздал, но ещё может прийти
        var future = booked("future", 12, minutesFromNow(60));
        var arrived = booked("arrived", 13, minutesFromNow(-2));
        tickets.checkIn(arrived, null, operator);

        timers.expireBookings();

        assertThat(status(gone)).isEqualTo("no_show");
        assertThat(jdbc.queryForObject("SELECT close_reason FROM tickets WHERE id=?", String.class, gone)).isEqualTo("appointment_expired");
        assertThat(count("SELECT count(*) FROM ticket_events WHERE ticket_id=? AND event_type='ticket.no_show'", gone)).isEqualTo(1);
        assertThat(status(recent)).isEqualTo("booked");
        assertThat(status(future)).isEqualTo("booked");
        assertThat(status(arrived)).isEqualTo("waiting");                          // пришедшая запись не истекает
    }

    @Test
    void expiryIsRepeatableWithoutSideEffects() {
        var gone = booked("gone2", 10, minutesFromNow(-90));
        timers.expireBookings();
        timers.expireBookings();
        assertThat(count("SELECT count(*) FROM ticket_events WHERE ticket_id=? AND event_type='ticket.no_show'", gone)).isEqualTo(1);
    }
}
