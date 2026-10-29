package honey.road.node.queue.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code uniform} vs {@code heuristic} on a deterministic simulation (sect 16.3) and the bounded-wait guarantee
 * (sect 6.6). Scenarios are chosen so the result can be derived by hand. The summary table goes to
 * {@code target/simulation/uniform-vs-heuristic.md} (synthetic).
 */
class UniformVsHeuristicTest {
    static final UUID FAST = UUID.fromString("00000000-0000-4000-8000-0000000000f1");
    static final UUID MEDIUM = UUID.fromString("00000000-0000-4000-8000-0000000000f2");
    static final UUID SLOW = UUID.fromString("00000000-0000-4000-8000-0000000000f3");
    static final Duration SLACK = Duration.ofMinutes(5);

    private static ToDoubleFunction<Ticket> uniform() {
        return t -> 1.0;
    }

    private static ToDoubleFunction<Ticket> heuristic(Map<UUID, Long> duration) {
        return t -> 1.0 / duration.get(t.serviceId());
    }

    // ------------------------------------------------------------------ hand-checkable scenarios

    /**
     * At 0 the window has two eligible heads within slack: parcels 600 s (deadline 2 min) and letters 60 s (4 min).
     * uniform goes by deadline: waits 0 and 600 → mean 300 s. heuristic takes the short one first: 0 and 60 → mean 30 s.
     */
    @Test
    void heuristicCutsMeanWaitWhenBothHeadsAreInsideSlack() {
        var duration = Map.of(SLOW, 600L, FAST, 60L);
        var jobs = List.of(new QueueSimulator.Job("A-slow", SLOW, 0, 120), new QueueSimulator.Job("B-fast", FAST, 0, 240));
        var windows = List.<Set<UUID>>of(Set.of(SLOW, FAST));

        var u = QueueSimulator.run(jobs, windows, duration, uniform(), SLACK);
        var h = QueueSimulator.run(jobs, windows, duration, heuristic(duration), SLACK);

        assertEquals("A-slow", u.picks().getFirst().job());
        assertEquals("B-fast", h.picks().getFirst().job());
        assertEquals(QueueSelection.REASON_SCORE, h.picks().getFirst().reason());
        assertEquals(300.0, u.meanWait(), 1e-9);
        assertEquals(30.0, h.meanWait(), 1e-9);
        assertEquals(u.makespanSec(), h.makespanSec());                      // same work: the gain is only in waiting
    }

    /**
     * Score does not override an overdue ticket: parcels are overdue (deadline 0), so even heuristic takes them first.
     */
    @Test
    void overdueTicketIsNeverBypassedByAShorterOne() {
        var duration = Map.of(SLOW, 600L, FAST, 60L);
        var jobs = List.of(new QueueSimulator.Job("A-slow", SLOW, 0, 0), new QueueSimulator.Job("B-fast", FAST, 0, 240));

        var h = QueueSimulator.run(jobs, List.of(Set.of(SLOW, FAST)), duration, heuristic(duration), SLACK);

        assertEquals("A-slow", h.picks().getFirst().job());
        assertEquals(QueueSelection.REASON_OVERDUE, h.picks().getFirst().reason());
    }

    /**
     * Bounded wait (6.6): a long service does not starve. One window; fast clients (30 s) arrive every 30 s and are always
     * queued; the long one (600 s, deadline 300 s) arrived first. While it is not overdue heuristic prefers the fast ones;
     * at 300 s it is overdue with a deadline earlier than all fast ones (≥ 330 s) and is taken as soon as the window frees:
     * wait exactly 300 s, never more than the deadline plus one fast service.
     */
    @Test
    void longServiceIsNotStarvedByAStreamOfShortOnes() {
        var duration = Map.of(SLOW, 600L, FAST, 30L);
        var jobs = new ArrayList<QueueSimulator.Job>();
        jobs.add(new QueueSimulator.Job("slow", SLOW, 0, 300));
        for (int k = 0; k < 40; k++) {
            jobs.add(new QueueSimulator.Job("fast-" + k, FAST, k * 30L, 300));
        }
        var windows = List.<Set<UUID>>of(Set.of(SLOW, FAST));

        var h = QueueSimulator.run(jobs, windows, duration, heuristic(duration), SLACK);
        var u = QueueSimulator.run(jobs, windows, duration, uniform(), SLACK);

        assertEquals(300L, h.waitSec().get("slow"));                          // deferred for the whole deadline, but no longer
        assertTrue(h.waitSec().get("slow") <= 300 + duration.get(FAST));      // bound from 6.6
        assertEquals(0L, u.waitSec().get("slow"));                            // EDF with equal deadlines: first come, first served
        var slowPick = h.picks().stream().filter(p -> p.job().equals("slow")).findFirst().orElseThrow();
        assertEquals(QueueSelection.REASON_OVERDUE, slowPick.reason());
    }

    // ------------------------------------------------------------------ summary on a "typical" stream

    /**
     * Pseudo-random stream (seed 42), two windows, three services. Only derivable facts are asserted: both strategies do the
     * same amount of work and lose nobody; report numbers go to a file.
     */
    @Test
    void randomizedDayIsServedCompletelyByBothStrategiesAndReportIsWritten() throws IOException {
        var duration = Map.of(FAST, 120L, MEDIUM, 300L, SLOW, 600L);
        var rnd = new Random(42);
        var jobs = new ArrayList<QueueSimulator.Job>();
        long t = 0;
        var services = List.of(FAST, FAST, MEDIUM, MEDIUM, SLOW);
        for (int i = 0; i < 120; i++) {
            t += 20 + rnd.nextInt(160);                                       // ~ once per 100 s on average
            jobs.add(new QueueSimulator.Job("c" + i, services.get(rnd.nextInt(services.size())), t, 1200));
        }
        var windows = List.<Set<UUID>>of(Set.of(FAST, MEDIUM, SLOW), Set.of(FAST, MEDIUM, SLOW));

        var u = QueueSimulator.run(jobs, windows, duration, uniform(), SLACK);
        var h = QueueSimulator.run(jobs, windows, duration, heuristic(duration), SLACK);

        assertEquals(jobs.size(), u.doneSec().size());
        assertEquals(jobs.size(), h.doneSec().size());
        long busy = jobs.stream().mapToLong(j -> duration.get(j.service())).sum();
        assertTrue(u.makespanSec() >= busy / 2 && h.makespanSec() >= busy / 2);   // lower bound from the load of two windows

        var report = new StringBuilder("# uniform vs heuristic (синтетика, seed 42)\n\n")
                .append("| Стратегия | Клиентов | Среднее ожидание, с | Максимум, с | Завершение работы, с |\n|---|---|---|---|---|\n");
        row(report, "uniform", u);
        row(report, "heuristic", h);
        var dir = Path.of("target", "simulation");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("uniform-vs-heuristic.md"), report.toString());
    }

    private static void row(StringBuilder sb, String name, QueueSimulator.Result r) {
        sb.append(String.format("| %s | %d | %.1f | %d | %d |%n", name, r.doneSec().size(), r.meanWait(), r.maxWait(), r.makespanSec()));
    }
}
