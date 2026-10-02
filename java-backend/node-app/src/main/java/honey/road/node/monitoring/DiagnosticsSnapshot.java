package honey.road.node.monitoring;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Diagnostics snapshot of the node for {@code diag.snapshot} (design 7). It is wanted exactly when something is wrong,
 * so every section runs on its own thread against one deadline: a section that throws or hangs (a locked database)
 * becomes {@code {"error": ...}} in its place and the rest is still answered. The answer must fit a NATS message:
 * over the limit, stack traces go first, then the largest sections are replaced by an error.
 */
@Component
public class DiagnosticsSnapshot {
    static final Duration DEADLINE = Duration.ofSeconds(8);                // the center waits 10 s
    static final int MAX_BYTES = 512 * 1024;                                // NATS message limit is 1 MB
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Section(String name, Supplier<Object> read) {
    }

    private final List<Section> sections;
    private final Duration deadline;
    private final int maxBytes;
    private final Clock clock;

    @Autowired
    public DiagnosticsSnapshot(SnapshotSections sections, @Qualifier("realClock") Clock clock) {
        this(sections.all(), DEADLINE, MAX_BYTES, clock);
    }

    DiagnosticsSnapshot(List<Section> sections, Duration deadline, int maxBytes, Clock clock) {
        this.sections = List.copyOf(sections); this.deadline = deadline; this.maxBytes = maxBytes; this.clock = clock;
    }

    public Map<String, Object> take() {
        var result = new LinkedHashMap<String, Object>();
        result.put("taken_at", clock.instant());
        result.put("truncated", false);
        var pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var futures = new ArrayList<Future<Object>>();
            for (var s : sections) {
                futures.add(pool.submit(() -> s.read().get()));
            }
            long until = System.nanoTime() + deadline.toNanos();
            for (int i = 0; i < sections.size(); i++) {
                result.put(sections.get(i).name(), await(futures.get(i), until));
            }
        } finally {
            pool.shutdownNow();                                             // close() would wait for a hung section
        }
        fit(result);
        return result;
    }

    private Object await(Future<Object> future, long until) {
        try {
            return future.get(Math.max(0, until - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return Map.of("error", "timed out after " + deadline.toMillis() + " ms");
        } catch (ExecutionException e) {
            var cause = e.getCause() == null ? e : e.getCause();
            return Map.of("error", cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of("error", "interrupted");
        }
    }

    /** Stack traces first; then the largest sections, one by one, until the answer fits. */
    private void fit(Map<String, Object> result) {
        if (size(result) <= maxBytes) {
            return;
        }
        result.put("truncated", true);
        for (var s : sections) {
            result.put(s.name(), withoutStacks(result.get(s.name())));
        }
        while (size(result) > maxBytes) {
            String largest = null;
            int largestSize = -1;
            for (var s : sections) {
                int n = size(result.get(s.name()));
                if (n > largestSize && !isTrimmed(result.get(s.name()))) {
                    largest = s.name();
                    largestSize = n;
                }
            }
            if (largest == null) {
                return;
            }
            result.put(largest, Map.of("error", "too large (" + largestSize / 1024 + " KB), trimmed", "trimmed", true));
        }
    }

    private static boolean isTrimmed(Object section) {
        return section instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("trimmed"));
    }

    private static Object withoutStacks(Object value) {
        if (value instanceof Map<?, ?> m) {
            var copy = new LinkedHashMap<Object, Object>();
            m.forEach((k, v) -> {
                if (!"stack".equals(k)) {
                    copy.put(k, withoutStacks(v));
                }
            });
            return copy;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(DiagnosticsSnapshot::withoutStacks).toList();
        }
        return value;
    }

    private static int size(Object value) {
        return JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length;
    }
}
