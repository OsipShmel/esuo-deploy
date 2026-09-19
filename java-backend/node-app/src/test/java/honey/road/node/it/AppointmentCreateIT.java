package honey.road.node.it;

import honey.road.node.queue.application.IssueTicketCommand;
import honey.road.node.queue.domain.DomainException;
import honey.road.node.queue.domain.TicketSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Проверка ТЗ №1: предварительная запись (веб) и запись из МП (внутренний вход). */
class AppointmentCreateIT extends AbstractNodeIT {
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");

    private static Instant slot(int daysAhead, int hour, int minute) {
        return LocalDate.now(ZONE).plusDays(daysAhead).atTime(hour, minute).atZone(ZONE).toInstant();
    }

    private IssueTicketCommand appointment(UUID service, Instant slot) {
        return new IssueTicketCommand(TicketSource.appointment, service, slot, "default", null, null);
    }

    @Test
    void appointmentIsBookedWithSlotAndDoesNotEnterQueueYet() {
        var slot = slot(1, 10, 0);
        var view = issue.execute(appointment(PARCEL, slot), "appt-1");

        assertThat(view.status()).isEqualTo("booked");
        assertThat(view.source()).isEqualTo("appointment");
        assertThat(view.slotStart()).isEqualTo(slot);
        // До check-in запись не участвует в выборе (4.2).
        openWindow(W1, E1, PARCEL);
        assertThatThrownBy(() -> callNext.execute(W1, null)).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("QUEUE_EMPTY");
    }

    @Test
    void repeatWithSameKeyReturnsSameTicketAndOccupiedSlotIsRejected() {
        var slot = slot(1, 10, 15);
        var first = issue.execute(appointment(PARCEL, slot), "same");
        var replay = issue.execute(appointment(PARCEL, slot), "same");
        assertThat(replay.ticketId()).isEqualTo(first.ticketId());
        assertThat(replay.token()).isEqualTo(first.token());

        assertThatThrownBy(() -> issue.execute(appointment(PARCEL, slot), "another")).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("SLOT_UNAVAILABLE");
        assertThatThrownBy(() -> issue.execute(appointment(PARCEL, slot(1, 11, 0)), "same")).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void cancelFreesTheSlot() {
        var slot = slot(1, 12, 0);
        var first = issue.execute(appointment(PARCEL, slot), "k1");
        cancel.execute(first.token());
        assertThat(issue.execute(appointment(PARCEL, slot), "k2").status()).isEqualTo("booked");
    }

    @Test
    void invalidSlotsAreRejected() {
        assertThatThrownBy(() -> issue.execute(appointment(PARCEL, Instant.now().minusSeconds(3600)), "past"))
                .isInstanceOf(DomainException.class).extracting(e -> ((DomainException) e).code()).isEqualTo("SLOT_EXPIRED");
        assertThatThrownBy(() -> issue.execute(appointment(PARCEL, slot(1, 10, 7)), "off-grid"))
                .isInstanceOf(DomainException.class).extracting(e -> ((DomainException) e).code()).isEqualTo("VALIDATION_ERROR");
        assertThatThrownBy(() -> issue.execute(appointment(PARCEL, slot(1, 3, 0)), "night"))
                .isInstanceOf(DomainException.class).extracting(e -> ((DomainException) e).code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void mobileAppointmentIsIdempotentByExternalId() {
        var slot = slot(2, 10, 0);
        var first = issue.fromMobileAppointment("mp-42", PARCEL, slot, "default");
        var again = issue.fromMobileAppointment("mp-42", PARCEL, slot, "default");
        assertThat(again.ticketId()).isEqualTo(first.ticketId());
        assertThat(count("SELECT count(*) FROM tickets WHERE mp_appointment_id='mp-42'")).isEqualTo(1);
    }

    @Test
    void mobileAppointmentForPassedSlotIsRejectedAsExpired() {
        assertThatThrownBy(() -> issue.fromMobileAppointment("mp-old", PARCEL, Instant.now().minusSeconds(600), "default"))
                .isInstanceOf(DomainException.class).extracting(e -> ((DomainException) e).code()).isEqualTo("SLOT_EXPIRED");
    }
}
