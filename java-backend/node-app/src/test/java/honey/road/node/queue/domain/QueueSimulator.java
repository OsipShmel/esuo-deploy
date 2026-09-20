package honey.road.node.queue.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToDoubleFunction;
import java.util.Arrays;
import java.util.function.Predicate;

/**
 * Deterministic queue simulator for comparing strategies (sect 16.3, 20.1). Uses the real {@link QueueSelection}:
 * the window that frees up first takes its services' queue heads (eligible, by {@code (due_at, seq)}) and picks by
 * the 6.4 rules. Nothing random: the result depends only on the input.
 */
final class QueueSimulator {
    static final Instant START = Instant.parse("2026-09-21T06:00:00Z");
    static final LocalDate DAY = LocalDate.of(2026, 9, 21);

    /** Client: service, arrival (seconds from start), deadline (seconds from arrival). */
    record Job(String name, UUID service, long arrivalSec, long dueOffsetSec) {
    }

    record Pick(String job, int window, long atSec, String reason) {
    }

    record Result(Map<String, Long> waitSec, Map<String, Long> doneSec, List<Pick> picks, long makespanSec) {
        double meanWait() {
            return waitSec.values().stream().mapToLong(Long::longValue).average().orElse(0);
        }

        long maxWait() {
            return waitSec.values().stream().mapToLong(Long::longValue).max().orElse(0);
        }

        long maxWait(Predicate<String> nameFilter) {
            return waitSec.entrySet().stream().filter(e -> nameFilter.test(e.getKey())).mapToLong(Map.Entry::getValue).max().orElse(0);
        }

        long completedBy(long horizonSec) {
            return doneSec.values().stream().filter(t -> t <= horizonSec).count();
        }
    }

    private QueueSimulator() {
    }

    /**
     * @param windows     services of each window
     * @param durationSec service duration per service, seconds
     * @param score       score for the choice within slack (uniform: 1; heuristic: 1 / duration)
     */
    static Result run(List<Job> jobs, List<Set<UUID>> windows, Map<UUID, Long> durationSec, ToDoubleFunction<Ticket> score,
                      Duration slack) {
        var tickets = new ArrayList<Ticket>();
        var jobOf = new HashMap<UUID, Job>();
        for (int i = 0; i < jobs.size(); i++) {
            var j = jobs.get(i);
            var arrival = START.plusSeconds(j.arrivalSec());
            var t = Ticket.fresh(UUID.randomUUID(), UUID.randomUUID(), "b01", j.service(), TicketSource.qr, TicketStatus.waiting,
                    "default", 1, j.name(), arrival, DAY, null, null, arrival, arrival.plusSeconds(j.dueOffsetSec()), "d" + i, null)
                    .withSeq(i);
            tickets.add(t);
            jobOf.put(t.id(), j);
        }
        var waiting = new ArrayList<>(tickets);
        var free = new long[windows.size()];
        var alive = new boolean[windows.size()];
        Arrays.fill(alive, true);

        var wait = new HashMap<String, Long>();
        var done = new HashMap<String, Long>();
        var picks = new ArrayList<Pick>();
        long makespan = 0;

        while (!waiting.isEmpty()) {
            int w = -1;
            for (int i = 0; i < free.length; i++) {
                if (alive[i] && (w < 0 || free[i] < free[w])) { w = i; }
            }
            if (w < 0) { break; }
            long at = free[w];
            var now = START.plusSeconds(at);

            var heads = new ArrayList<Ticket>();
            for (var service : windows.get(w)) {
                waiting.stream()
                        .filter(t -> t.serviceId().equals(service) && !t.eligibleFrom().isAfter(now))
                        .min(Comparator.comparing(Ticket::dueAt).thenComparingLong(Ticket::seq))
                        .ifPresent(heads::add);
            }
            if (heads.isEmpty()) {
                final int window = w;
                var next = waiting.stream().filter(t -> windows.get(window).contains(t.serviceId()))
                        .map(t -> t.eligibleFrom().getEpochSecond() - START.getEpochSecond()).min(Long::compare);
                if (next.isEmpty()) { alive[w] = false; } else { free[w] = Math.max(next.get(), at + 1); }
                continue;
            }
            var choice = QueueSelection.choose(heads, now, slack, score);
            var chosen = choice.chosen();
            waiting.remove(chosen);
            var job = jobOf.get(chosen.id());
            wait.put(job.name(), at - job.arrivalSec());
            free[w] = at + durationSec.get(job.service());
            done.put(job.name(), free[w]);
            makespan = Math.max(makespan, free[w]);
            picks.add(new Pick(job.name(), w, at, choice.reason()));
        }
        return new Result(wait, done, picks, makespan);
    }
}
