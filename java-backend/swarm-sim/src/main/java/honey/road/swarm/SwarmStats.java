package honey.road.swarm;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counters of a run: per event type published / acknowledged / failed / dropped as duplicates, heartbeats and config
 * messages. The load script compares {@code acked} with what the center wrote to {@code events}.
 */
final class SwarmStats {
    final Map<String, LongAdder> published = new ConcurrentHashMap<>();
    final Map<String, LongAdder> acked = new ConcurrentHashMap<>();
    final Map<String, LongAdder> failed = new ConcurrentHashMap<>();
    final LongAdder duplicates = new LongAdder();
    final LongAdder heartbeats = new LongAdder();
    final LongAdder heartbeatErrors = new LongAdder();
    final LongAdder configs = new LongAdder();
    final Map<String, LongAdder> errors = new ConcurrentHashMap<>();

    void inc(Map<String, LongAdder> map, String key) {
        map.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    void error(Throwable e) {
        var cause = e.getCause() != null ? e.getCause() : e;
        inc(errors, cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage()).lines().findFirst().orElse(""));
    }

    static long total(Map<String, LongAdder> map) {
        return map.values().stream().mapToLong(LongAdder::sum).sum();
    }

    static Map<String, Long> sorted(Map<String, LongAdder> map) {
        var out = new TreeMap<String, Long>();
        map.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }
}
