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
import java.util.Comparator;

/**
 * Simplified queue simulation for ETA (sect 6.8). Pure function, no DB and no ambient time.
 * <p>Waiting tickets go to open windows by the same rule as {@link QueueSelection} with {@code uniform} scoring:
 * the window that frees up first takes the next eligible ticket of its services. A ticket's ETA is the moment it
 * gets a window. Tickets no window can take (no open window for the service, targeted window closed) are absent
 * from the result: ETA undefined (S2).
 */
public final class QueueSimulation {
    private QueueSimulation() {
    }

    /**
     * @param windowId window
     * @param services window services
     * @param freeAt   when the window frees up: {@code now} if free, end of the predicted service if busy
     */
    public record WindowState(UUID windowId, Set<UUID> services, Instant freeAt) {
    }

    /** @param eta assignment moment for every ticket that got a window */
    public record Result(Map<UUID, Instant> eta) {
    }

    /** Predicted service duration of a ticket in a given window (the window defines the employee), seconds. */
    @FunctionalInterface
    public interface DurationModel {
        long seconds(WindowState window, Ticket ticket);
    }

    /** Per-service estimate, same for all windows. */
    public static Result run(Instant now, List<Ticket> waiting, List<WindowState> windows,
                             ToLongFunction<UUID> durationSec, Duration slack) {
        return run(now, waiting, windows, (w, t) -> durationSec.applyAsLong(t.serviceId()), slack);
    }

    /**
     * @param waiting  waiting tickets in EDF order {@code (due_at, seq)}
     * @param windows  windows accepting new clients (open, not {@code closing})
     * @param duration predicted service duration of a ticket in a window, seconds
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
            var window = live.stream().min(Comparator.comparing((WindowState w) -> free.get(w.windowId()))
                    .thenComparing(w -> w.windowId().toString())).orElseThrow();
            var at = free.get(window.windowId());

            var heads = new ArrayList<Ticket>();
            for (var service : window.services()) {
                remaining.stream()
                        .filter(t -> t.serviceId().equals(service) && servedBy(t, window) && !t.eligibleFrom().isAfter(at))
                        .findFirst().ifPresent(heads::add);
            }
            if (heads.isEmpty()) {
                // The window idles until an eligible ticket appears for it.
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
