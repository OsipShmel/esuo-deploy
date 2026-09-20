package honey.road.node.queue.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Selection algorithm (sect 6.4) on deterministic data. */
class QueueSelectionTest {
    static final Instant NOW = Instant.parse("2026-09-21T07:00:00Z");
    static final Duration SLACK = Duration.ofMinutes(5);

    static Ticket t(String number, long seq, int dueInMin) {
        return Ticket.fresh(UUID.randomUUID(), UUID.randomUUID(), "b01", UUID.randomUUID(), TicketSource.qr,
                        TicketStatus.waiting, "default", 1, number, NOW.minusSeconds(600), LocalDate.of(2026, 9, 21),
                        null, null, NOW.minusSeconds(600), NOW.plus(Duration.ofMinutes(dueInMin)), "d" + seq, null)
                .withSeq(seq);
    }

    @Test
    void overdueWinsAndScoreIsIgnored() {
        var overdueLate = t("П-001", 1, -1);
        var overdueEarly = t("О-001", 2, -5);
        var fresh = t("Ф-001", 3, 2);
        var r = QueueSelection.choose(List.of(overdueLate, overdueEarly, fresh), NOW, SLACK,
                x -> x == fresh ? 100 : 1);
        assertEquals(overdueEarly, r.chosen());
        assertEquals(QueueSelection.REASON_OVERDUE, r.reason());
    }

    @Test
    void scoreChoosesInsideSlack() {
        var a = t("П-001", 1, 1);
        var b = t("О-001", 2, 4);
        var r = QueueSelection.choose(List.of(a, b), NOW, SLACK, x -> x == b ? 2 : 1);
        assertEquals(b, r.chosen());
        assertEquals(QueueSelection.REASON_SCORE, r.reason());
    }

    @Test
    void uniformScoreFallsBackToEdfThenSeq() {
        var a = t("П-002", 5, 3);
        var b = t("О-001", 2, 3);
        var r = QueueSelection.choose(List.of(a, b), NOW, SLACK, x -> 1);
        assertEquals(b, r.chosen());
    }

    @Test
    void outsideSlackIsPureEdf() {
        var a = t("П-001", 1, 10);
        var b = t("О-001", 2, 20);
        var r = QueueSelection.choose(List.of(b, a), NOW, SLACK, x -> x == b ? 100 : 1);
        assertEquals(a, r.chosen());
        assertEquals(QueueSelection.REASON_EDF, r.reason());
    }
}
