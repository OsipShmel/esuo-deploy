package honey.road.node.it;

import honey.road.contracts.event.Actor;
import honey.road.node.queue.application.ApproachingNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверка ТЗ №8, часть отделения: при недоступности уведомлений очередь работает (раздел 12, S11).
 * В тестовой среде NATS недоступен, поэтому outbox-воркер ничего не публикует — ровно то состояние, в котором
 * отделение оказывается при обрыве связи. Доставку, ретраи, circuit breaker и DLQ проверяет центр (notify worker).
 */
class NotificationOutageIT extends AbstractNodeIT {
    private final Actor operator = Actor.operator(E1.toString());

    @Autowired ApproachingNotifier notifier;

    private long pending(String subjectSuffix) {
        return count("SELECT count(*) FROM outbox WHERE status='pending' AND subject LIKE ?", "%" + subjectSuffix);
    }

    @Test
    void queueKeepsWorkingWhileNotificationRequestsAccumulateInTheOutbox() {
        openWindow(W1, E1, PARCEL);
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 6; i++) ids.add(qr(PARCEL, "c" + i));
        assertThat(notifier.scan()).isEqualTo(4);                                       // запросы уведомлений созданы

        // Полный цикл обслуживания идёт как обычно, никаких ошибок из-за недоступной доставки.
        for (var id : ids) {
            assertThat(callNext.execute(W1, null).ticketId()).isEqualTo(id);
            tickets.start(id, null, operator);
            tickets.complete(id, null, operator);
        }
        assertThat(count("SELECT count(*) FROM tickets WHERE status='done'")).isEqualTo(6);

        // События не потеряны и ждут восстановления связи: ни одно не помечено отправленным и не «сгорело» повторами.
        assertThat(pending("notification.requested")).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM outbox WHERE status <> 'pending'")).isZero();
        assertThat(count("SELECT count(*) FROM outbox WHERE attempts <> 0")).isZero();
    }

    @Test
    void eachNotificationIsRequestedOnceAndCarriesADedupKeyForTheCenter() {
        openWindow(W1, E1, PARCEL);
        for (int i = 0; i < 3; i++) qr(PARCEL, "d" + i);

        notifier.scan();
        notifier.scan();                                                                // повторный проход — никаких дублей
        notifier.scan();

        assertThat(pending("notification.requested")).isEqualTo(3);
        assertThat(count("SELECT count(DISTINCT payload->'data'->>'dedup_key') FROM outbox WHERE subject LIKE '%notification.requested'"))
                .isEqualTo(3);
    }

    @Test
    void outboxKeepsTheOrderOfEventsOfOneTicketWhileDeliveryIsDown() {
        openWindow(W1, E1, PARCEL);
        var id = qr(PARCEL, "ordered");
        notifier.scan();
        callNext.execute(W1, null);
        tickets.complete(id, null, operator);

        var types = jdbc.queryForList("""
                SELECT payload->>'event_type' FROM outbox WHERE payload->>'aggregate_id' = ? ORDER BY seq
                """, String.class, id.toString());
        assertThat(types).containsExactly("ticket.created", "notification.requested", "ticket.called", "ticket.completed");
    }

    @Test
    void notificationFlagIsSetInTheDatabaseSoARestartDoesNotRepeatIt() {
        openWindow(W1, E1, PARCEL);
        var view = qrView(PARCEL, "flag");
        notifier.scan();
        assertThat(count("SELECT count(*) FROM tickets WHERE id=? AND notified_approaching_at IS NOT NULL", view.ticketId())).isEqualTo(1);
        assertThat(notifier.scan()).isZero();
    }
}
