package honey.road.node.it;

import honey.road.node.queue.application.IssueTicketCommand;
import honey.road.node.queue.application.Walkin;
import honey.road.node.queue.domain.DomainException;
import honey.road.node.queue.domain.TicketSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import honey.road.contracts.event.Actor;

/** Check #3: a free window calls a client; EDF choice, decision journal. */
class CallNextIT extends AbstractNodeIT {
    @Autowired Walkin walkin;

    @Test
    void closedWindowAndEmptyQueueAreRejected() {
        assertThatThrownBy(() -> callNext.execute(W1, null)).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("WINDOW_NOT_OPEN");
        openWindow(W1, E1, PARCEL);
        assertThatThrownBy(() -> callNext.execute(W1, null)).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("QUEUE_EMPTY");
    }

    @Test
    void earlierDeadlineWinsWithinService() {
        openWindow(W1, E1, PARCEL);
        var ordinary = qr(PARCEL, "d1", "default");      // due = issue + 20 min
        var pensioner = qr(PARCEL, "d2", "pensioner");   // due = issue + 15 min, although issued later
        var first = callNext.execute(W1, null);
        assertThat(first.ticketId()).isEqualTo(pensioner);
        assertThat(status(ordinary)).isEqualTo("waiting");
    }

    @Test
    void headsOfDifferentServicesAreComparedByDeadline() {
        openWindow(W1, E1, PARCEL, SEND);
        qr(PARCEL, "p", "default");
        var urgent = qr(SEND, "s", "svo");               // due = +5 min
        assertThat(callNext.execute(W1, null).ticketId()).isEqualTo(urgent);
    }

    @Test
    void windowDoesNotCallServicesItDoesNotServe() {
        openWindow(W1, E1, PARCEL);
        qr(FINANCE, "f");
        assertThatThrownBy(() -> callNext.execute(W1, null)).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("QUEUE_EMPTY");
    }

    @Test
    void decisionIsJournaledWithCandidatesAndOutboxEvent() {
        openWindow(W1, E1, PARCEL, SEND);
        qr(PARCEL, "p");
        qr(SEND, "s");
        var called = callNext.execute(W1, null);

        var payload = jdbc.queryForObject("SELECT payload::text FROM ticket_events WHERE ticket_id=? AND event_type='ticket.called'",
                String.class, called.ticketId());
        assertThat(payload).contains("reason").contains("candidates").contains("scoring_strategy").contains("uniform");
        assertThat(count("SELECT count(*) FROM outbox WHERE subject LIKE '%ticket.called' AND status='pending'")).isEqualTo(1);
        assertThat(count("SELECT call_count FROM tickets WHERE id=?", called.ticketId())).isEqualTo(1);
    }

    @Test
    void walkinWithCallNowIsCalledImmediately() {
        openWindow(W1, E1, PARCEL);
        var view = walkin.execute(W1, PARCEL, "default", true, E1, "walkin-1");
        assertThat(view.status()).isEqualTo("called");
        assertThat(view.window().id()).isEqualTo(W1);
    }

    @Test
    void completeFreesWindowForNextCall() {
        openWindow(W1, E1, PARCEL);
        var a = qr(PARCEL, "a");
        var b = qr(PARCEL, "b");
        var first = callNext.execute(W1, null);
        assertThat(first.ticketId()).isEqualTo(a);
        tickets.start(a, null, Actor.operator(E1.toString()));
        tickets.complete(a, null, Actor.operator(E1.toString()));
        assertThat(callNext.execute(W1, null).ticketId()).isEqualTo(b);
    }
}
