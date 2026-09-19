package honey.road.node.it;

import honey.road.contracts.event.Actor;
import honey.road.node.queue.domain.DomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Проверка ТЗ №5: закрытие окна с активным клиентом (S1) и сброс адресного окна (S4). */
class CloseWindowIT extends AbstractNodeIT {

    @Test
    void forceCloseReturnsTicketToHeadWithSameDeadline() {
        openWindow(W1, E1, PARCEL);
        var a = qr(PARCEL, "a");
        var b = qr(PARCEL, "b");
        assertThat(callNext.execute(W1, null).ticketId()).isEqualTo(a);
        var dueBefore = jdbc.queryForObject("SELECT due_at FROM tickets WHERE id=?", java.sql.Timestamp.class, a);

        var closed = windows.close(W1, "force", E1);

        assertThat(closed.status()).isEqualTo("closed");
        assertThat(status(a)).isEqualTo("waiting");
        assertThat(jdbc.queryForObject("SELECT window_id FROM tickets WHERE id=?", java.util.UUID.class, a)).isNull();
        assertThat(jdbc.queryForObject("SELECT due_at FROM tickets WHERE id=?", java.sql.Timestamp.class, a)).isEqualTo(dueBefore);

        // Другое окно с той же услугой берёт именно его первым, а не b: место сохранено.
        openWindow(W2, E2, PARCEL);
        assertThat(callNext.execute(W2, null).ticketId()).isEqualTo(a);
        assertThat(status(b)).isEqualTo("waiting");
    }

    @Test
    void gracefulCloseWaitsForCurrentClient() {
        openWindow(W1, E1, PARCEL);
        var a = qr(PARCEL, "a");
        qr(PARCEL, "b");
        callNext.execute(W1, null);

        var closing = windows.close(W1, "graceful", E1);
        assertThat(closing.status()).isEqualTo("closing");
        assertThatThrownBy(() -> callNext.execute(W1, null)).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("WINDOW_NOT_OPEN");
        assertThat(status(a)).isEqualTo("called");

        tickets.complete(a, null, Actor.operator(E1.toString()));
        assertThat(jdbc.queryForObject("SELECT status FROM windows WHERE id=?", String.class, W1)).isEqualTo("closed");
    }

    @Test
    void closeWithoutActiveTicketClosesImmediatelyAndIsIdempotent() {
        openWindow(W1, E1, PARCEL);
        assertThat(windows.close(W1, "graceful", E1).status()).isEqualTo("closed");
        assertThat(windows.close(W1, "force", E1).status()).isEqualTo("closed");
    }

    @Test
    void ticketAddressedToClosedWindowLosesTarget() {
        openWindow(W1, E1, PARCEL);
        var a = qr(PARCEL, "a");
        tickets.redirect(a, null, W1, "к специалисту", null, Actor.operator(E1.toString()));
        assertThat(jdbc.queryForObject("SELECT target_window_id FROM tickets WHERE id=?", java.util.UUID.class, a)).isEqualTo(W1);

        windows.close(W1, "graceful", E1);

        assertThat(jdbc.queryForObject("SELECT target_window_id FROM tickets WHERE id=?", java.util.UUID.class, a)).isNull();
        assertThat(count("SELECT count(*) FROM ticket_events WHERE ticket_id=? AND event_type='ticket.redirected'", a)).isEqualTo(2);
    }
}
