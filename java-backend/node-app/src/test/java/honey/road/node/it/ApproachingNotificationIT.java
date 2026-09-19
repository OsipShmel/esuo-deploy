package honey.road.node.it;

import honey.road.contracts.event.Actor;
import honey.road.node.queue.application.ApproachingNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уведомление «очередь приближается» (4.7). Правило из queue-defaults: position ≤ 3 или ETA ≤ 5 мин.
 * Один открытый оператор на услугу «Посылки», прогноз обслуживания по умолчанию 300 с.
 */
class ApproachingNotificationIT extends AbstractNodeIT {
    @Autowired ApproachingNotifier notifier;

    private long requests() {
        return count("SELECT count(*) FROM outbox WHERE subject LIKE '%notification.requested'");
    }

    @Test
    void onlyTicketsWithinThresholdAreNotifiedAndOnlyOnce() {
        openWindow(W1, E1, PARCEL);
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 6; i++) ids.add(qr(PARCEL, "d" + i));

        // Позиции 0..3 подпадают под правило, 4 и 5 — нет (позиция > 3 и ETA далеко).
        assertThat(notifier.scan()).isEqualTo(4);
        assertThat(requests()).isEqualTo(4);
        assertThat(notifiedIds()).containsExactlyInAnyOrderElementsOf(ids.subList(0, 4));

        // Повторный проход ничего не добавляет: флаг в БД, а не в памяти.
        assertThat(notifier.scan()).isZero();
        assertThat(requests()).isEqualTo(4);
    }

    @Test
    void ticketIsNotifiedWhenQueueMovesToIt() {
        openWindow(W1, E1, PARCEL);
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 6; i++) ids.add(qr(PARCEL, "d" + i));
        notifier.scan();

        callNext.execute(W1, null);                                    // ids[0] вызван
        tickets.complete(ids.get(0), null, Actor.operator(E1.toString()));

        assertThat(notifier.scan()).isEqualTo(1);                      // ids[4] стал четвёртым в очереди
        assertThat(notifiedIds()).contains(ids.get(4)).doesNotContain(ids.get(5));
        assertThat(requests()).isEqualTo(5);
    }

    @Test
    void noOpenWindowMeansNoNotification() {
        qr(PARCEL, "a");
        assertThat(notifier.scan()).isZero();
        assertThat(requests()).isZero();
    }

    @Test
    void postponedTicketIsNotNotifiedWhileNotEligible() {
        openWindow(W1, E1, PARCEL);
        var view = qrView(PARCEL, "late");
        tickets.postpone(view.token(), 10);
        assertThat(notifier.scan()).isZero();
        assertThat(notifiedIds()).doesNotContain(view.ticketId());
    }

    @Test
    void eventCarriesDedupKeyAndNeverTheClientSecret() {
        openWindow(W1, E1, PARCEL);
        var view = qrView(PARCEL, "one");
        notifier.scan();

        var payload = jdbc.queryForObject("SELECT payload::text FROM outbox WHERE subject LIKE '%notification.requested'", String.class);
        assertThat(payload).contains(view.ticketId() + ":approaching").contains("token_hash").contains("\"approaching\"");
        assertThat(payload).doesNotContain(view.token().toString());
        assertThat(jdbc.queryForObject("SELECT status FROM outbox WHERE subject LIKE '%notification.requested'", String.class))
                .isEqualTo("pending");
    }

    @Test
    void estimateReflectsSimulationWithTwoWindows() {
        openWindow(W1, E1, PARCEL);
        openWindow(W2, E2, PARCEL);
        var first = qrView(PARCEL, "a");
        qrView(PARCEL, "b");
        var third = qrView(PARCEL, "c");

        // Два окна свободны: первые двое стоят «сейчас», третий ждёт одно обслуживание (~300 с).
        var eta0 = queries.get(first.token()).eta();
        var eta2 = queries.get(third.token()).eta();
        assertThat(eta2).isAfter(eta0.plusSeconds(290)).isBefore(eta0.plusSeconds(310));
        assertThat(queries.get(third.token()).position()).isEqualTo(2);
    }

    @Test
    void ticketWithoutOpenWindowShowsUndefinedEta() {
        var view = qrView(PARCEL, "nowin");
        var read = queries.get(view.token());
        assertThat(read.eta()).isNull();
        assertThat(read.etaConfidence()).isEqualTo("none");
    }

    private List<UUID> notifiedIds() {
        return jdbc.queryForList("SELECT id FROM tickets WHERE notified_approaching_at IS NOT NULL", UUID.class);
    }
}
