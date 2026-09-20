package honey.road.node.queue.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToDoubleFunction;
import java.util.HashMap;

/**
 * Ticket selection when a window frees up (sect 6.4). Pure function of the window's queue heads, {@code now},
 * {@code slack} and score, so the decision is reproducible from the journal.
 * <ol>
 *   <li>Overdue heads ({@code due_at <= now}): earliest deadline, score ignored.</li>
 *   <li>Several heads within {@code now + slack}: max score, then (due_at, seq).</li>
 *   <li>Otherwise plain EDF by (due_at, seq).</li>
 * </ol>
 */
public final class QueueSelection {
    public static final String REASON_OVERDUE = "overdue_edf";
    public static final String REASON_SCORE = "score_in_slack";
    public static final String REASON_EDF = "edf";

    private static final Comparator<Ticket> EDF =
            Comparator.comparing(Ticket::dueAt).thenComparingLong(Ticket::seq);

    private QueueSelection() {
    }

    public record Result(Ticket chosen, String reason, Map<UUID, Double> scores) {
    }

    public static Result choose(List<Ticket> heads, Instant now, Duration slack, ToDoubleFunction<Ticket> score) {
        if (heads.isEmpty()) {
            throw new IllegalArgumentException("no heads");
        }
        var overdue = heads.stream().filter(t -> !t.dueAt().isAfter(now)).toList();
        if (!overdue.isEmpty()) {
            return new Result(overdue.stream().min(EDF).orElseThrow(), REASON_OVERDUE, Map.of());
        }
        var inSlack = heads.stream().filter(t -> !t.dueAt().isAfter(now.plus(slack))).toList();
        if (inSlack.size() > 1) {
            var scores = new HashMap<UUID, Double>();
            inSlack.forEach(t -> scores.put(t.id(), score.applyAsDouble(t)));
            var chosen = inSlack.stream()
                    .min(Comparator.<Ticket>comparingDouble(t -> -scores.get(t.id())).thenComparing(EDF))
                    .orElseThrow();
            return new Result(chosen, REASON_SCORE, Map.copyOf(scores));
        }
        return new Result(heads.stream().min(EDF).orElseThrow(), REASON_EDF, Map.of());
    }
}
