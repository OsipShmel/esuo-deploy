package honey.road.node.queue.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Симуляция ETA (раздел 6.8) на детерминированных данных, без БД. */
class QueueSimulationTest {
    static final Instant NOW = Instant.parse("2026-09-21T07:00:00Z");
    static final Duration SLACK = Duration.ofMinutes(5);
    static final UUID PARCEL = UUID.randomUUID();
    static final UUID FINANCE = UUID.randomUUID();
    static final long DURATION = 300;

    private long seq;

    private Ticket ticket(UUID service, int dueInMin, int eligibleInMin, UUID target) {
        var t = Ticket.fresh(UUID.randomUUID(), UUID.randomUUID(), "b01", service, TicketSource.qr, TicketStatus.waiting,
                "default", 1, "T-" + (++seq), NOW, LocalDate.of(2026, 9, 21), null, null,
                NOW.plus(Duration.ofMinutes(eligibleInMin)), NOW.plus(Duration.ofMinutes(dueInMin)), "d" + seq, null);
        return new Ticket(t.id(), t.publicToken(), t.branchId(), t.serviceId(), t.source(), t.status(), t.clientCategory(),
                t.configVersion(), seq, t.displayNumber(), t.issuedAt(), t.issuedDay(), null, null, t.eligibleFrom(), t.dueAt(),
                null, null, target, t.deviceId(), null, NOW, 0, null, 0, null, null, null, 0);
    }

    private Ticket ticket(UUID service, int dueInMin) {
        return ticket(service, dueInMin, 0, null);
    }

    private static QueueSimulation.WindowState window(UUID id, Instant freeAt, UUID... services) {
        return new QueueSimulation.WindowState(id, Set.of(services), freeAt);
    }

    private static QueueSimulation.Result run(List<Ticket> waiting, QueueSimulation.WindowState... windows) {
        return QueueSimulation.run(NOW, waiting, List.of(windows), s -> DURATION, SLACK);
    }

    @Test
    void oneWindowServesQueueSequentially() {
        var a = ticket(PARCEL, 10);
        var b = ticket(PARCEL, 11);
        var c = ticket(PARCEL, 12);
        var r = run(List.of(a, b, c), window(UUID.randomUUID(), NOW, PARCEL));
        assertEquals(NOW, r.eta().get(a.id()));
        assertEquals(NOW.plusSeconds(300), r.eta().get(b.id()));
        assertEquals(NOW.plusSeconds(600), r.eta().get(c.id()));
    }

    @Test
    void twoWindowsWithSharedServiceHalveTheWait() {
        var tickets = new ArrayList<Ticket>();
        for (int i = 0; i < 4; i++) tickets.add(ticket(PARCEL, 10 + i));
        var r = run(tickets, window(UUID.randomUUID(), NOW, PARCEL), window(UUID.randomUUID(), NOW, PARCEL));
        assertEquals(NOW, r.eta().get(tickets.get(0).id()));
        assertEquals(NOW, r.eta().get(tickets.get(1).id()));
        assertEquals(NOW.plusSeconds(300), r.eta().get(tickets.get(2).id()));
        assertEquals(NOW.plusSeconds(300), r.eta().get(tickets.get(3).id()));
    }

    @Test
    void busyWindowStartsFromItsRemainingTime() {
        var a = ticket(PARCEL, 10);
        var r = run(List.of(a), window(UUID.randomUUID(), NOW.plusSeconds(120), PARCEL));
        assertEquals(NOW.plusSeconds(120), r.eta().get(a.id()));
    }

    @Test
    void windowIdlesUntilTicketBecomesEligible() {
        var early = ticket(PARCEL, 30, 10, null);          // допуск через 10 минут
        var r = run(List.of(early), window(UUID.randomUUID(), NOW, PARCEL));
        assertEquals(NOW.plusSeconds(600), r.eta().get(early.id()));
    }

    @Test
    void notYetEligibleTicketDoesNotBlockLaterOnesThatAreReady() {
        var later = ticket(PARCEL, 5, 10, null);           // дедлайн раньше, но допуск позже
        var ready = ticket(PARCEL, 20);
        var r = run(List.of(later, ready), window(UUID.randomUUID(), NOW, PARCEL));
        assertEquals(NOW, r.eta().get(ready.id()));
        assertEquals(NOW.plusSeconds(600), r.eta().get(later.id()));
    }

    @Test
    void ticketWithoutOpenWindowHasNoEta() {
        var stuck = ticket(FINANCE, 10);
        var ok = ticket(PARCEL, 10);
        var r = run(List.of(stuck, ok), window(UUID.randomUUID(), NOW, PARCEL));
        assertFalse(r.eta().containsKey(stuck.id()));
        assertTrue(r.eta().containsKey(ok.id()));
    }

    @Test
    void addressedTicketWaitsForItsOwnWindow() {
        var w1 = UUID.randomUUID();
        var w2 = UUID.randomUUID();
        var addressed = ticket(PARCEL, 10, 0, w2);
        var r = run(List.of(addressed), window(w1, NOW, PARCEL), window(w2, NOW.plusSeconds(200), PARCEL));
        assertEquals(NOW.plusSeconds(200), r.eta().get(addressed.id()));
    }

    @Test
    void addressedTicketToClosedWindowHasNoEta() {
        var addressed = ticket(PARCEL, 10, 0, UUID.randomUUID());
        var r = run(List.of(addressed), window(UUID.randomUUID(), NOW, PARCEL));
        assertFalse(r.eta().containsKey(addressed.id()));
    }

    @Test
    void multiServiceWindowTakesEarlierDeadlineFirst() {
        var parcel = ticket(PARCEL, 20);
        var finance = ticket(FINANCE, 12);
        var r = run(List.of(finance, parcel), window(UUID.randomUUID(), NOW, PARCEL, FINANCE));
        assertEquals(NOW, r.eta().get(finance.id()));
        assertEquals(NOW.plusSeconds(300), r.eta().get(parcel.id()));
    }

    @Test
    void noWindowsMeansNoEtaAndNoLoop() {
        var r = run(List.of(ticket(PARCEL, 10)));
        assertTrue(r.eta().isEmpty());
    }
}
