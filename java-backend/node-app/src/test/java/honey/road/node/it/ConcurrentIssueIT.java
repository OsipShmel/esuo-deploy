package honey.road.node.it;

import honey.road.contracts.dto.TicketView;
import honey.road.node.queue.application.IssueTicketCommand;
import honey.road.node.queue.domain.DomainException;
import honey.road.node.queue.domain.TicketSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Проверка ТЗ №9: конкурентное создание талонов без дублирования. */
class ConcurrentIssueIT extends AbstractNodeIT {
    private static final int THREADS = 20;

    /** Результат одной попытки: либо талон, либо код доменной ошибки. */
    private record Outcome(TicketView view, String errorCode) {}

    @Test
    void sameIdempotencyKeyCreatesSingleTicket() throws Exception {
        var command = new IssueTicketCommand(TicketSource.qr, PARCEL, null, "default", "phone-1", null);
        var outcomes = race(THREADS, i -> issue.execute(command, "same-key"));

        assertThat(outcomes).allSatisfy(o -> assertThat(o.errorCode()).isNull());
        assertThat(outcomes).extracting(o -> o.view().ticketId()).containsOnly(outcomes.getFirst().view().ticketId());
        assertThat(outcomes).extracting(o -> o.view().token()).containsOnly(outcomes.getFirst().view().token());
        assertThat(count("SELECT count(*) FROM tickets")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ticket_events WHERE event_type='ticket.created'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox")).isEqualTo(1);
    }

    @Test
    void sameDeviceWithDifferentKeysGetsOneActiveTicket() throws Exception {
        var outcomes = race(THREADS, i -> issue.execute(
                new IssueTicketCommand(TicketSource.qr, PARCEL, null, "default", "phone-2", null), "key-" + i));

        assertThat(outcomes.stream().filter(o -> o.view() != null)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> o.view() == null))
                .allSatisfy(o -> assertThat(o.errorCode()).isEqualTo("ACTIVE_TICKET_EXISTS"));
        assertThat(count("SELECT count(*) FROM tickets WHERE device_id='phone-2'")).isEqualTo(1);
    }

    @Test
    void parallelIssueGivesUniqueNumbersAndNoLoss() throws Exception {
        int n = 100;
        var outcomes = race(n, i -> issue.execute(
                new IssueTicketCommand(TicketSource.qr, PARCEL, null, "default", "dev-" + i, null), "k-" + i));

        assertThat(outcomes).allSatisfy(o -> assertThat(o.errorCode()).isNull());
        assertThat(outcomes).extracting(o -> o.view().displayNumber()).doesNotHaveDuplicates().hasSize(n);
        assertThat(count("SELECT count(*) FROM tickets")).isEqualTo(n);
        assertThat(count("SELECT count(DISTINCT display_number) FROM tickets")).isEqualTo(n);
        assertThat(count("SELECT count(DISTINCT seq) FROM tickets")).isEqualTo(n);
        assertThat(count("SELECT count(*) FROM outbox")).isEqualTo(n);
    }

    @FunctionalInterface
    private interface Attempt {
        TicketView run(int i) throws Exception;
    }

    private List<Outcome> race(int threads, Attempt attempt) throws Exception {
        var pool = Executors.newFixedThreadPool(Math.min(threads, 32));
        try {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<Outcome>>();
            IntStream.range(0, threads).forEach(i -> futures.add(pool.submit(() -> {
                start.await();
                try {
                    return new Outcome(attempt.run(i), null);
                } catch (DomainException e) {
                    return new Outcome(null, e.code());
                }
            })));
            start.countDown();
            var result = new ArrayList<Outcome>();
            for (var f : futures) {
                result.add(f.get(60, TimeUnit.SECONDS));
            }
            return result;
        } finally {
            pool.shutdownNow();
        }
    }
}
