package honey.road.node.queue.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToLongFunction;

/**
 * Упрощённая симуляция очереди для ETA (раздел 6.8). Чистая функция, без БД и времени из окружения.
 * <p>Ожидающие талоны раздаются открытым окнам тем же правилом, что и {@link QueueSelection} при
 * {@code uniform}-скоринге: окно, которое освободится раньше, берёт следующий допущенный талон своих услуг.
 * ETA талона — момент, когда симуляция назначит его окну. Талон, который не достался ни одному окну
 * (нет открытого окна с его услугой, адресное окно закрыто), в результат не попадает — ETA «не определено» (S2).
 */
public final class QueueSimulation {
    private QueueSimulation() {
    }

    /**
     * @param windowId  окно
     * @param services  услуги окна
     * @param freeAt    когда окно освободится: {@code now} для свободного, конец прогнозного обслуживания для занятого
     */
    public record WindowState(UUID windowId, Set<UUID> services, Instant freeAt) {
    }

    /** @param eta момент назначения окну для каждого талона, которому нашлось окно */
    public record Result(Map<UUID, Instant> eta) {
    }

    /** Прогноз длительности обслуживания талона в конкретном окне (окно даёт сотрудника), секунд. */
    @FunctionalInterface
    public interface DurationModel {
        long seconds(WindowState window, Ticket ticket);
    }

    /** Прогноз по услуге, одинаковый для всех окон. */
    public static Result run(Instant now, List<Ticket> waiting, List<WindowState> windows,
                             ToLongFunction<UUID> durationSec, Duration slack) {
        return run(now, waiting, windows, (w, t) -> durationSec.applyAsLong(t.serviceId()), slack);
    }

    /**
     * @param waiting     ожидающие талоны в порядке EDF {@code (due_at, seq)}
     * @param windows     окна, принимающие новых клиентов (открытые, не {@code closing})
     * @param duration    прогноз длительности обслуживания талона окном, секунд
     */
    public static Result run(Instant now, List<Ticket> waiting, List<WindowState> windows,
                             DurationModel duration, Duration slack) {
        var remaining = new ArrayList<>(waiting);
        var free = new HashMap<UUID, Instant>();
        var live = new ArrayList<WindowState>();
        for (var w : windows) {
            live.add(w);
            free.put(w.windowId(), w.freeAt().isBefore(now) ? now : w.freeAt());
        }
        var eta = new HashMap<UUID, Instant>();

        while (!remaining.isEmpty() && !live.isEmpty()) {
            var window = live.stream().min(java.util.Comparator.comparing((WindowState w) -> free.get(w.windowId()))
                    .thenComparing(w -> w.windowId().toString())).orElseThrow();
            var at = free.get(window.windowId());

            var heads = new ArrayList<Ticket>();
            for (var service : window.services()) {
                remaining.stream()
                        .filter(t -> t.serviceId().equals(service) && servedBy(t, window) && !t.eligibleFrom().isAfter(at))
                        .findFirst().ifPresent(heads::add);
            }
            if (heads.isEmpty()) {
                // Окно простаивает до ближайшего момента, когда для него появится допущенный талон.
                var next = remaining.stream()
                        .filter(t -> window.services().contains(t.serviceId()) && servedBy(t, window)
                                && t.eligibleFrom().isAfter(at))
                        .map(Ticket::eligibleFrom).min(Instant::compareTo);
                if (next.isEmpty()) {
                    live.remove(window);
                } else {
                    free.put(window.windowId(), next.get());
                }
                continue;
            }
            var chosen = QueueSelection.choose(heads, at, slack, t -> 1.0).chosen();
            eta.put(chosen.id(), at);
            remaining.remove(chosen);
            free.put(window.windowId(), at.plusSeconds(Math.max(1, duration.seconds(window, chosen))));
        }
        return new Result(Map.copyOf(eta));
    }

    private static boolean servedBy(Ticket t, WindowState w) {
        return t.targetWindowId() == null || t.targetWindowId().equals(w.windowId());
    }
}
