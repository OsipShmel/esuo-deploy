package honey.road.node.it;

import honey.road.contracts.event.Actor;
import honey.road.node.queue.domain.DomainException;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Откладывание талона клиентом (раздел 5.4, 6.3): допуск сдвигается, дедлайн остаётся, лимиты из конфигурации
 * ({@code postpone-max-count: 1}, {@code postpone-max-min: 30}).
 */
class PostponeIT extends AbstractNodeIT {

    private static String code(Throwable e) {
        return ((DomainException) e).code();
    }

    private Instant column(UUID ticket, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM tickets WHERE id=?", Timestamp.class, ticket).toInstant();
    }

    @Test
    void postponedTicketIsNotCallableUntilItsNewEligibleTimeButKeepsItsDeadline() {
        openWindow(W1, E1, PARCEL);
        var view = qrView(PARCEL, "p1");
        var due = column(view.ticketId(), "due_at");

        var postponed = tickets.postpone(view.token(), 10);

        assertThat(postponed.status()).isEqualTo("waiting");
        assertThat(column(view.ticketId(), "due_at")).isEqualTo(due);                              // дедлайн без изменений
        assertThat(Duration.between(Instant.now(), column(view.ticketId(), "eligible_from")).toMinutes()).isBetween(9L, 10L);
        assertThat(count("SELECT postpone_count FROM tickets WHERE id=?", view.ticketId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ticket_events WHERE ticket_id=? AND event_type='ticket.postponed'", view.ticketId())).isEqualTo(1);
        assertThatThrownBy(() -> callNext.execute(W1, null)).isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("QUEUE_EMPTY"));
    }

    @Test
    void afterTheDelayThePostponedTicketGoesBeforeNewerOnesBecauseItsDeadlineIsOlder() {
        openWindow(W1, E1, PARCEL);
        var older = qrView(PARCEL, "older");
        var newer = qr(PARCEL, "newer");

        tickets.postpone(older.token(), 5);
        assertThat(callNext.execute(W1, null).ticketId()).isEqualTo(newer);                        // пока отложен — вызывают другого
        tickets.complete(newer, null, Actor.operator(E1.toString()));

        jdbc.update("UPDATE tickets SET eligible_from = now() - interval '1 minute' WHERE id=?", older.ticketId());   // «время вышло»
        assertThat(callNext.execute(W1, null).ticketId()).isEqualTo(older.ticketId());
    }

    @Test
    void postponeLimitIsOnePerTicketByDefault() {
        var view = qrView(PARCEL, "once");
        assertThat(queries.get(view.token()).canPostpone()).isTrue();

        tickets.postpone(view.token(), 5);

        assertThat(queries.get(view.token()).canPostpone()).isFalse();
        assertThatThrownBy(() -> tickets.postpone(view.token(), 5)).isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("INVALID_STATE_TRANSITION"));
        assertThat(count("SELECT postpone_count FROM tickets WHERE id=?", view.ticketId())).isEqualTo(1);
    }

    @Test
    void minutesOutsideTheConfiguredRangeAreRejected() {
        var view = qrView(PARCEL, "range");
        assertThatThrownBy(() -> tickets.postpone(view.token(), 0)).isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("VALIDATION_ERROR"));
        assertThatThrownBy(() -> tickets.postpone(view.token(), 31)).isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("VALIDATION_ERROR"));
        tickets.postpone(view.token(), 30);                                                        // граница допустима
        assertThat(count("SELECT postpone_count FROM tickets WHERE id=?", view.ticketId())).isEqualTo(1);
    }

    @Test
    void onlyWaitingTicketsCanBePostponed() {
        openWindow(W1, E1, PARCEL);
        var view = qrView(PARCEL, "called");
        callNext.execute(W1, null);

        assertThatThrownBy(() -> tickets.postpone(view.token(), 5)).isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("INVALID_STATE_TRANSITION"));
        assertThat(queries.get(view.token()).canPostpone()).isFalse();
    }

    @Test
    void postponedTicketShowsLaterEtaThanBefore() {
        openWindow(W1, E1, PARCEL);
        var view = qrView(PARCEL, "eta");
        var before = queries.get(view.token()).eta();

        tickets.postpone(view.token(), 20);

        var after = queries.get(view.token()).eta();
        assertThat(after).isAfter(before.plus(Duration.ofMinutes(15)));                            // ETA не раньше конца отсрочки
    }
}
