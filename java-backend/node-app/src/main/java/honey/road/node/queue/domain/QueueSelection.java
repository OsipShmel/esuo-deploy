package honey.road.node.queue.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

/**
 * Выбор талона при освобождении окна (раздел 6.4). Чистая функция: результат зависит только от голов
 * очередей услуг окна, {@code now}, {@code slack} и score — поэтому решение воспроизводимо по журналу.
 * <ol>
 *   <li>Есть просроченные ({@code due_at <= now}) — самый ранний дедлайн среди них, score не действует.</li>
 *   <li>Несколько голов в пределах {@code now + slack} — максимальный score, затем (due_at, seq).</li>
 *   <li>Иначе — чистый EDF по (due_at, seq).</li>
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
            var scores = new java.util.HashMap<UUID, Double>();
            inSlack.forEach(t -> scores.put(t.id(), score.applyAsDouble(t)));
            var chosen = inSlack.stream()
                    .min(Comparator.<Ticket>comparingDouble(t -> -scores.get(t.id())).thenComparing(EDF))
                    .orElseThrow();
            return new Result(chosen, REASON_SCORE, Map.copyOf(scores));
        }
        return new Result(heads.stream().min(EDF).orElseThrow(), REASON_EDF, Map.of());
    }
}
