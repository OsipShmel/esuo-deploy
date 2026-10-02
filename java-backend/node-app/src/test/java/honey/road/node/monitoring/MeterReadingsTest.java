package honey.road.node.monitoring;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Micrometer part of the {@code sys} block (design 6.1): a meter that is not there leaves its field out. */
class MeterReadingsTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MeterReadings readings = new MeterReadings(registry);

    private Map<String, Object> read() {
        var sys = new LinkedHashMap<String, Object>();
        readings.putInto(sys);
        return sys;
    }

    @Test
    void absentMetersWriteNothing() {
        assertThat(read()).doesNotContainKeys("cpu_process", "cpu_system", "load_avg", "heap_used_mb", "heap_max_mb",
                "gc_pause_ms", "db_pool_active", "db_pool_pending", "db_pool_max", "log_errors", "log_warns");
    }

    @Test
    void logEventsAreDeltasPerInterval() {
        var errors = registry.counter("logback.events", "level", "error");
        var warns = registry.counter("logback.events", "level", "warn");
        errors.increment(5);
        warns.increment(1);
        assertThat(read()).containsEntry("log_errors", 5L).containsEntry("log_warns", 1L);

        errors.increment(2);

        assertThat(read()).containsEntry("log_errors", 2L).containsEntry("log_warns", 0L);
    }

    @Test
    void logEventsAsFunctionCounters() {                                   // what Micrometer's LogbackMetrics registers
        var errors = new AtomicLong(3);
        FunctionCounter.builder("logback.events", errors, AtomicLong::get)
                .tag("level", "error").register(registry);
        read();

        errors.addAndGet(4);

        assertThat(read()).containsEntry("log_errors", 4L);
    }

    @Test
    void heapInMegabytesSummedOverPools() {
        Gauge.builder("jvm.memory.used", () -> 2 * 1024 * 1024).tags(Tags.of("area", "heap", "id", "eden")).register(registry);
        Gauge.builder("jvm.memory.used", () -> 1024 * 1024).tags(Tags.of("area", "heap", "id", "old")).register(registry);
        Gauge.builder("jvm.memory.used", () -> 50 * 1024 * 1024).tags(Tags.of("area", "nonheap", "id", "meta")).register(registry);
        Gauge.builder("jvm.memory.max", () -> 512 * 1024 * 1024).tags(Tags.of("area", "heap", "id", "old")).register(registry);
        Gauge.builder("jvm.memory.max", () -> -1).tags(Tags.of("area", "heap", "id", "eden")).register(registry);

        assertThat(read()).containsEntry("heap_used_mb", 3L).containsEntry("heap_max_mb", 512L);
    }

    @Test
    void gcPauseIsADeltaInMilliseconds() {
        var young = registry.timer("jvm.gc.pause", "gc", "young");
        young.record(Duration.ofMillis(30));
        read();

        young.record(Duration.ofMillis(5));
        registry.timer("jvm.gc.pause", "gc", "old").record(Duration.ofMillis(10));

        assertThat(read()).containsEntry("gc_pause_ms", 15L);
    }

    @Test
    void cpuAndPool() {
        Gauge.builder("process.cpu.usage", () -> 0.125).register(registry);
        Gauge.builder("system.cpu.usage", () -> 0.5).register(registry);
        Gauge.builder("system.load.average.1m", () -> -1).register(registry);       // not available on this OS
        Gauge.builder("hikaricp.connections.active", () -> 2).tag("pool", "p").register(registry);
        Gauge.builder("hikaricp.connections.pending", () -> 0).tag("pool", "p").register(registry);
        Gauge.builder("hikaricp.connections.max", () -> 10).tag("pool", "p").register(registry);

        assertThat(read()).containsEntry("cpu_process", 0.125).containsEntry("cpu_system", 0.5)
                .doesNotContainKey("load_avg")
                .containsEntry("db_pool_active", 2L).containsEntry("db_pool_pending", 0L).containsEntry("db_pool_max", 10L);
    }
}
