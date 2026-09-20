package honey.road.node.it;

import honey.road.contracts.event.Actor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code auto_call_on_free} (sect 6.4): auto-call on {@code open} and after {@code complete}; off by default. */
class AutoCallOnFreeIT extends AbstractNodeIT {
    private final Actor operator = Actor.operator(E1.toString());

    private void autoCall(boolean on) {
        jdbc.update("UPDATE branch_config SET central_config = jsonb_set(central_config, '{auto_call_on_free}', ?::jsonb) WHERE id=1",
                Boolean.toString(on));
    }

    @Test
    void disabledByDefaultOperatorCallsManually() {
        var a = qr(PARCEL, "a");
        openWindow(W1, E1, PARCEL);
        assertThat(status(a)).isEqualTo("waiting");

        callNext.execute(W1, null);
        var b = qr(PARCEL, "b");
        tickets.complete(a, null, operator);
        assertThat(status(b)).isEqualTo("waiting");
    }

    @Test
    void openingWindowCallsNextWhenEnabled() {
        autoCall(true);
        var a = qr(PARCEL, "a");
        var b = qr(PARCEL, "b");

        openWindow(W1, E1, PARCEL);

        assertThat(status(a)).isEqualTo("called");
        assertThat(status(b)).isEqualTo("waiting");
        assertThat(count("SELECT count(*) FROM tickets WHERE window_id=? AND status='called'", W1)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ticket_events WHERE ticket_id=? AND event_type='ticket.called'", a)).isEqualTo(1);
    }

    @Test
    void completeCallsNextWhenEnabled() {
        autoCall(true);
        var a = qr(PARCEL, "a");
        var b = qr(PARCEL, "b");
        openWindow(W1, E1, PARCEL);                                    // a called automatically

        tickets.complete(a, null, operator);

        assertThat(status(a)).isEqualTo("done");
        assertThat(status(b)).isEqualTo("called");
    }

    @Test
    void emptyQueueIsNotAnErrorAndDoesNotRollBackTheCommand() {
        autoCall(true);
        openWindow(W1, E1, PARCEL);                                    // empty queue: the window is just open
        var a = qr(PARCEL, "a");
        callNext.execute(W1, null);

        tickets.complete(a, null, operator);                           // queue drained: complete still succeeds

        assertThat(status(a)).isEqualTo("done");
        assertThat(jdbc.queryForObject("SELECT status FROM windows WHERE id=?", String.class, W1)).isEqualTo("open");
    }

    @Test
    void closingWindowDoesNotAutoCallAfterComplete() {
        autoCall(true);
        var a = qr(PARCEL, "a");
        var b = qr(PARCEL, "b");
        openWindow(W1, E1, PARCEL);                                    // a called
        windows.close(W1, "graceful", E1);

        tickets.complete(a, null, operator);

        assertThat(status(b)).isEqualTo("waiting");
        assertThat(jdbc.queryForObject("SELECT status FROM windows WHERE id=?", String.class, W1)).isEqualTo("closed");
    }
}
