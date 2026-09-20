package honey.road.node.it;

import honey.road.contracts.event.Actor;
import honey.road.node.queue.domain.DomainException;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Check #6: redirect to another service (S3) and to a specific window (S4). */
class RedirectIT extends AbstractNodeIT {
    private final Actor operator = Actor.operator(E1.toString());

    @Test
    void redirectToAnotherServiceKeepsDeadline() {
        var a = qr(PARCEL, "a");
        var due = jdbc.queryForObject("SELECT due_at FROM tickets WHERE id=?", Timestamp.class, a);

        var view = tickets.redirect(a, SEND, null, "ошибся услугой", null, operator);

        assertThat(view.service().id()).isEqualTo(SEND);
        assertThat(jdbc.queryForObject("SELECT due_at FROM tickets WHERE id=?", Timestamp.class, a)).isEqualTo(due);
        assertThat(status(a)).isEqualTo("waiting");
    }

    @Test
    void redirectOfActiveTicketReturnsItToQueueOfNewService() {
        openWindow(W1, E1, PARCEL);
        openWindow(W3, E2, SEND);
        var a = qr(PARCEL, "a");
        callNext.execute(W1, null);

        tickets.redirect(a, SEND, null, "нужна другая услуга", null, operator);

        assertThat(status(a)).isEqualTo("waiting");
        assertThat(jdbc.queryForObject("SELECT window_id FROM tickets WHERE id=?", UUID.class, a)).isNull();
        assertThat(callNext.execute(W3, null).ticketId()).isEqualTo(a);
    }

    @Test
    void ticketAddressedToWindowCanBeCalledOnlyByThatWindow() {
        openWindow(W1, E1, PARCEL);
        openWindow(W2, E2, PARCEL);
        var a = qr(PARCEL, "a");
        tickets.redirect(a, null, W2, "пройдите в окно 2", null, operator);

        assertThatThrownBy(() -> callNext.execute(W1, null)).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("QUEUE_EMPTY");
        assertThat(callNext.execute(W2, null).ticketId()).isEqualTo(a);
    }

    @Test
    void redirectToWindowThatDoesNotServeTheServiceIsRejected() {
        var a = qr(PARCEL, "a");
        assertThatThrownBy(() -> tickets.redirect(a, null, W3, "x", null, operator)).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("SERVICE_NOT_IN_WINDOW");
    }

    @Test
    void redirectRequiresTargetAndRespectsVersion() {
        var a = qr(PARCEL, "a");
        assertThatThrownBy(() -> tickets.redirect(a, null, null, "x", null, operator)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> tickets.redirect(a, SEND, null, "x", 99, operator)).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("STALE_STATE");
    }
}
