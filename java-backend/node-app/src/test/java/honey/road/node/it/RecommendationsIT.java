package honey.road.node.it;

import honey.road.node.queue.application.Recommendations;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Window-opening recommendation (sect 6.8). Service estimate 300 s, default QR wait target 20 min;
 * threshold: ETA past the deadline by more than 10 min or more than 5 overdue.
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
        qr(SEND, "a");                                          // no windows at all

        var r = recommendations.current();

        assertThat(r.recommendOpenWindow()).isTrue();
        var send = r.services().getFirst();
        assertThat(send.serviceId()).isEqualTo(SEND);
        assertThat(send.reasons()).contains(Recommendations.REASON_NO_OPEN_WINDOW);
        assertThat(send.openWindows()).isZero();
        // Closed windows serving send: window 1 and window 3.
        assertThat(send.suggestedWindows()).extracting(Recommendations.SuggestedWindow::windowId).containsExactlyInAnyOrder(W1, W3);
    }

    @Test
    void growingQueueOnOneWindowRecommendsOpeningAnotherAndSecondWindowClearsIt() {
        openWindow(W1, E1, PARCEL);
        for (int i = 0; i < 12; i++) qr(PARCEL, "q" + i);      // ETA of the k-th ticket = k·5 min, deadline +20 min

        var before = recommendations.current();
        assertThat(before.recommendOpenWindow()).isTrue();
        var parcel = before.services().getFirst();
        assertThat(parcel.reasons()).containsExactly(Recommendations.REASON_ETA_OVERRUN);
        assertThat(parcel.maxEtaOverrunSec()).isBetween(34 * 60L, 36 * 60L);       // 11·5 − 20 = 35 min
        assertThat(parcel.suggestedWindows()).extracting(Recommendations.SuggestedWindow::windowId).contains(W2);

        openWindow(W2, E2, PARCEL);                             // a second window halves the wait: overrun 5 min < 10
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
        assertThat(parcel.overdue()).isEqualTo(6);                                // above the threshold of 5
        assertThat(parcel.reasons()).contains(Recommendations.REASON_OVERDUE_COUNT);

        // Exactly five overdue is not enough yet.
        jdbc.update("UPDATE tickets SET due_at = now() + interval '5 minutes' WHERE id = (SELECT id FROM tickets ORDER BY seq LIMIT 1)");
        assertThat(recommendations.current().services().stream()
                .anyMatch(s -> s.reasons().contains(Recommendations.REASON_OVERDUE_COUNT))).isFalse();
    }

    @Test
    void mostOverrunServiceComesFirst() {
        openWindow(W1, E1, PARCEL);                             // no send at all, parcels queue grows
        for (int i = 0; i < 12; i++) qr(PARCEL, "p" + i);
        qr(SEND, "s");

        var services = recommendations.current().services();
        assertThat(services).hasSize(2);
        assertThat(services.getFirst().serviceId()).isEqualTo(PARCEL);             // larger ETA overrun
    }
}
