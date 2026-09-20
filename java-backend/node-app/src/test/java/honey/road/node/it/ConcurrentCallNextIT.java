package honey.road.node.it;

import honey.road.contracts.dto.TicketView;
import honey.road.contracts.event.Actor;
import honey.road.node.queue.domain.DomainException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Assertions;

/**
 * Check #4: simultaneous calls from two windows without double assignment.
 * Two windows share a service; several threads per window start together each round.
 */
class ConcurrentCallNextIT extends AbstractNodeIT {
    private static final int TICKETS = 40;
    private static final int THREADS_PER_WINDOW = 4;
    private static final Set<String> EXPECTED = Set.of("WINDOW_BUSY", "QUEUE_EMPTY");

    @Test
    void twoWindowsWithSharedServiceNeverAssignTicketTwice() throws Exception {
        openWindow(W1, E1, PARCEL, SEND);
        openWindow(W2, E2, PARCEL, FINANCE);
        for (int i = 0; i < TICKETS; i++) {
            qr(PARCEL, "device-" + i);
        }

        var served = new HashSet<UUID>();
        int doubleRounds = 0;
        var pool = Executors.newFixedThreadPool(2 * THREADS_PER_WINDOW);
        try {
            for (int round = 0; round < TICKETS; round++) {
                var start = new CountDownLatch(1);
                var futures = new ArrayList<Future<TicketView>>();
                for (var window : List.of(W1, W2)) {
                    for (int t = 0; t < THREADS_PER_WINDOW; t++) {
                        futures.add(pool.submit(() -> {
                            start.await();
                            try {
                                return callNext.execute(window, null);
                            } catch (DomainException e) {
                                assertThat(EXPECTED).as("неожиданная ошибка: %s", e.getMessage()).contains(e.code());
                                return null;
                            }
                        }));
                    }
                }
                start.countDown();
                var winners = new ArrayList<TicketView>();
                for (var f : futures) {
                    var view = get(f);
                    if (view != null) {
                        winners.add(view);
                    }
                }
                if (winners.isEmpty()) {
                    break;
                }
                // At most one assignment per window per round, and no ticket goes to two windows.
                if (winners.size() == 2) {
                    doubleRounds++;
                }
                assertThat(winners).extracting(v -> v.window().id()).doesNotHaveDuplicates();
                assertThat(winners).extracting(TicketView::ticketId).doesNotHaveDuplicates();
                for (var w : winners) {
                    assertThat(served.add(w.ticketId())).as("талон %s назначен повторно", w.displayNumber()).isTrue();
                    tickets.complete(w.ticketId(), null, Actor.operator(E1.toString()));
                }
            }
        } finally {
            pool.shutdownNow();
        }

        // Tail where SKIP LOCKED could return empty under a foreign lock is drained sequentially.
        while (true) {
            var rest = tryCall(W1);
            if (rest == null) {
                break;
            }
            assertThat(served.add(rest.ticketId())).isTrue();
            tickets.complete(rest.ticketId(), null, Actor.operator(E1.toString()));
        }

        // Not degenerate: in most rounds both windows really got a ticket at the same time.
        assertThat(doubleRounds).isGreaterThan(TICKETS / 4);
        assertThat(served).hasSize(TICKETS);
        assertThat(count("SELECT count(*) FROM tickets WHERE status='done'")).isEqualTo(TICKETS);
        assertThat(count("SELECT count(*) FROM tickets WHERE status='waiting'")).isZero();
        // Every ticket called exactly once (journal is the audit source of truth).
        assertThat(count("SELECT count(*) FROM (SELECT ticket_id FROM ticket_events WHERE event_type='ticket.called' "
                + "GROUP BY ticket_id HAVING count(*) > 1) d")).isZero();
        assertThat(count("SELECT count(*) FROM ticket_events WHERE event_type='ticket.called'")).isEqualTo(TICKETS);
        // Last line: at most one active ticket per window.
        assertThat(count("SELECT count(*) FROM tickets WHERE status IN ('called','serving')")).isZero();
    }

    @Test
    void repeatedCallOnSameWindowIsRejectedWhileBusy() {
        openWindow(W1, E1, PARCEL);
        qr(PARCEL, "a");
        qr(PARCEL, "b");
        var first = callNext.execute(W1, null);
        assertThat(first.status()).isEqualTo("called");
        var e = Assertions.assertThrows(DomainException.class, () -> callNext.execute(W1, null));
        assertThat(e.code()).isEqualTo("WINDOW_BUSY");
        assertThat(count("SELECT count(*) FROM tickets WHERE status='called'")).isEqualTo(1);
    }

    private TicketView tryCall(UUID window) {
        try {
            return callNext.execute(window, null);
        } catch (DomainException e) {
            assertThat(e.code()).isEqualTo("QUEUE_EMPTY");
            return null;
        }
    }

    private static <T> T get(Future<T> f) throws Exception {
        try {
            return f.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }
}
