package honey.road.node.it;

import honey.road.node.queue.application.Recommendations;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Рекомендация открыть окно (6.8). Прогноз обслуживания 300 с, талон QR по умолчанию ждёт до 20 мин,
 * порог рекомендации — ETA позже дедлайна больше чем на 10 мин или просроченных больше 5.
 */
class RecommendationsIT extends AbstractNodeIT {
    @Autowired Recommendations recommendations;

    @Test
    void quietBranchHasNoRecommendation() {
        openWindow(W1, E1, PARCEL);
        qr(PARCEL, "a");
        var r = recommendations.current();
        assertThat(r.recommendOpenWindow()).isFalse();
        assertThat(r.services()).isEmpty();
    }

    @Test
    void waitingClientsWithoutAnOpenWindowAreReportedWithSuggestedWindows() {
        qr(SEND, "a");                                          // окон нет вообще

        var r = recommendations.current();

        assertThat(r.recommendOpenWindow()).isTrue();
        var send = r.services().getFirst();
        assertThat(send.serviceId()).isEqualTo(SEND);
        assertThat(send.reasons()).contains(Recommendations.REASON_NO_OPEN_WINDOW);
        assertThat(send.openWindows()).isZero();
        // Закрытые окна, которые обслуживают «Отправку»: окно 1 и окно 3.
        assertThat(send.suggestedWindows()).extracting(Recommendations.SuggestedWindow::windowId).containsExactlyInAnyOrder(W1, W3);
    }

    @Test
    void growingQueueOnOneWindowRecommendsOpeningAnotherAndSecondWindowClearsIt() {
        openWindow(W1, E1, PARCEL);
        for (int i = 0; i < 12; i++) qr(PARCEL, "q" + i);      // ETA k-го талона = k·5 мин, дедлайн +20 мин

        var before = recommendations.current();
        assertThat(before.recommendOpenWindow()).isTrue();
        var parcel = before.services().getFirst();
        assertThat(parcel.reasons()).containsExactly(Recommendations.REASON_ETA_OVERRUN);
        assertThat(parcel.maxEtaOverrunSec()).isBetween(34 * 60L, 36 * 60L);       // 11·5 − 20 = 35 мин
        assertThat(parcel.suggestedWindows()).extracting(Recommendations.SuggestedWindow::windowId).contains(W2);

        openWindow(W2, E2, PARCEL);                             // второе окно вдвое сокращает ожидание: перебор 5 мин < 10
        assertThat(recommendations.current().recommendOpenWindow()).isFalse();
    }

    @Test
    void manyOverdueTicketsTriggerTheCountRule() {
        openWindow(W1, E1, PARCEL);
        for (int i = 0; i < 6; i++) {
            var id = qr(PARCEL, "o" + i);
            jdbc.update("UPDATE tickets SET due_at = now() - interval '1 minute' WHERE id=?", id);
        }
        var parcel = recommendations.current().services().getFirst();
        assertThat(parcel.overdue()).isEqualTo(6);                                // больше порога 5
        assertThat(parcel.reasons()).contains(Recommendations.REASON_OVERDUE_COUNT);

        // Ровно пять просроченных — ещё не повод.
        jdbc.update("UPDATE tickets SET due_at = now() + interval '5 minutes' WHERE id = (SELECT id FROM tickets ORDER BY seq LIMIT 1)");
        assertThat(recommendations.current().services().stream()
                .anyMatch(s -> s.reasons().contains(Recommendations.REASON_OVERDUE_COUNT))).isFalse();
    }

    @Test
    void mostOverrunServiceComesFirst() {
        openWindow(W1, E1, PARCEL);                             // «Отправки» нет вовсе, у «Посылок» очередь растёт
        for (int i = 0; i < 12; i++) qr(PARCEL, "p" + i);
        qr(SEND, "s");

        var services = recommendations.current().services();
        assertThat(services).hasSize(2);
        assertThat(services.getFirst().serviceId()).isEqualTo(PARCEL);             // перебор ETA больше
    }
}
