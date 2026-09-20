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
 * "Approaching" notification (sect 4.7). Rule from queue-defaults: position ≤ 3 or ETA ≤ 5 min.
 * One open operator for parcels, default service estimate 300 s.
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

        // Positions 0..3 match, 4 and 5 do not (position > 3 and ETA far away).
        assertThat(notifier.scan()).isEqualTo(4);
        assertThat(requests()).isEqualTo(4);
        assertThat(notifiedIds()).containsExactlyInAnyOrderElementsOf(ids.subList(0, 4));

        // A repeated pass adds nothing: the flag is in the DB, not in memory.
        assertThat(notifier.scan()).isZero();
        assertThat(requests()).isEqualTo(4);
    }

    @Test
    void ticketIsNotifiedWhenQueueMovesToIt() {
        openWindow(W1, E1, PARCEL);
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 6; i++) ids.add(qr(PARCEL, "d" + i));
        notifier.scan();

        callNext.execute(W1, null);                                    // ids[0] called
        tickets.complete(ids.get(0), null, Actor.operator(E1.toString()));

        assertThat(notifier.scan()).isEqualTo(1);                      // ids[4] is now fourth
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

        // Two free windows: the first two are "now", the third waits one service (~300 s).
        var eta0 = queries.get(first.token()).eta();
        var eta2 = queries.get(third.token()).eta();
        assertThat(eta2).isAfter(eta0.plusSeconds(290)).isBefore(eta0.plusSeconds(310));
        assertThat(queries.get(third.token()).position()).isEqualTo(2);
    }

    /** Sect 16.3: heuristic ETA uses the open window employee's speed (cascade), uniform the service average. */
    @Test
    void heuristicEtaUsesEmployeeSpeed() {
        jdbc.update("UPDATE branch_config SET central_config = jsonb_set(central_config, '{scoring_strategy}', '\"heuristic\"') WHERE id=1");
        jdbc.update("""
                INSERT INTO handling_stats(service_id, employee_id, window_id, client_category, avg_duration_sec, sample_count, updated_at)
                VALUES (?, NULL, NULL, 'default', 300, 20, now()), (?, ?, NULL, 'default', 60, 10, now())
                """, PARCEL, PARCEL, E1);
        openWindow(W1, E1, PARCEL);                                    // fast employee: ~60 s
        var first = qrView(PARCEL, "a");
        var second = qrView(PARCEL, "b");

        var eta0 = queries.get(first.token()).eta();
        var eta1 = queries.get(second.token()).eta();
        assertThat(eta1).isAfter(eta0.plusSeconds(55)).isBefore(eta0.plusSeconds(65));
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
