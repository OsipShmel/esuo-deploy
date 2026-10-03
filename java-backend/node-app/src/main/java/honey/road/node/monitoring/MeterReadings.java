package honey.road.node.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The Micrometer part of the {@code sys} block (design 6.1): CPU, heap, GC, DB pool and log counters. A meter that is
 * not registered leaves its field out. Counters are reported as the increase since the previous reading.
 */
public final class MeterReadings {
    private static final long MB = 1024 * 1024;

    private final MeterRegistry registry;
    private double gcTotalMs = -1;
    private double logErrors;
    private double logWarns;

    public MeterReadings(MeterRegistry registry) {
        this.registry = registry;
    }

    public synchronized void putInto(Map<String, Object> sys) {
        putRatio(sys, "cpu_process", gauge("process.cpu.usage"));
        putRatio(sys, "cpu_system", gauge("system.cpu.usage"));
        putRatio(sys, "load_avg", gauge("system.load.average.1m"));
        putLong(sys, "heap_used_mb", sumGauges(registry.find("jvm.memory.used").tag("area", "heap")), MB);
        putLong(sys, "heap_max_mb", sumGauges(registry.find("jvm.memory.max").tag("area", "heap")), MB);
        var timers = registry.find("jvm.gc.pause").timers();
        if (!timers.isEmpty()) {
            double total = timers.stream().mapToDouble(t -> t.totalTime(TimeUnit.MILLISECONDS)).sum();
            sys.put("gc_pause_ms", Math.round(total - Math.max(gcTotalMs, 0)));
            gcTotalMs = total;
        }
        putLong(sys, "db_pool_active", sumGauges(registry.find("hikaricp.connections.active")), 1);
        putLong(sys, "db_pool_pending", sumGauges(registry.find("hikaricp.connections.pending")), 1);
        putLong(sys, "db_pool_max", sumGauges(registry.find("hikaricp.connections.max")), 1);
        var errors = count(registry.find("logback.events").tag("level", "error"));
        var warns = count(registry.find("logback.events").tag("level", "warn"));
        if (errors != null) {
            sys.put("log_errors", Math.round(errors - logErrors));
            logErrors = errors;
        }
        if (warns != null) {
            sys.put("log_warns", Math.round(warns - logWarns));
            logWarns = warns;
        }
    }

    /** Micrometer's logback binder registers function counters; a plain counter is accepted too. */
    private static @Nullable Double count(Search search) {
        var counter = search.counter();
        if (counter != null) {
            return counter.count();
        }
        var function = search.functionCounter();
        return function == null ? null : function.count();
    }

    private @Nullable Double gauge(String name) {
        var g = registry.find(name).gauge();
        return g == null ? null : g.value();
    }

    /** Sum over all tags (heap pools, DB pools); negative values mean "undefined" and are skipped. */
    private static @Nullable Double sumGauges(Search search) {
        var gauges = search.gauges();
        if (gauges.isEmpty()) {
            return null;
        }
        double sum = 0;
        boolean any = false;
        for (Gauge g : gauges) {
            double v = g.value();
            if (!Double.isNaN(v) && v >= 0) {
                sum += v;
                any = true;
            }
        }
        return any ? sum : null;
    }

    private static void putRatio(Map<String, Object> sys, String key, @Nullable Double value) {
        if (value != null && !Double.isNaN(value) && value >= 0) {
            sys.put(key, Math.round(value * 1000) / 1000.0);
        }
    }

    private static void putLong(Map<String, Object> sys, String key, @Nullable Double value, long unit) {
        if (value != null) {
            sys.put(key, Math.round(value / unit));
        }
    }
}
