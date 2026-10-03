package honey.road.center.diagnostics;

import com.zaxxer.hikari.HikariDataSource;
import honey.road.center.monitoring.CenterMeters;
import honey.road.center.nats.CenterNats;
import honey.road.center.network.BranchOnboarding;
import honey.road.center.network.HeartbeatBuffer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.management.ObjectName;
import javax.sql.DataSource;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A snapshot of the center itself (scale design 6.2), the counterpart of a branch snapshot: which threads burn the
 * CPU over one second — Java threads with their stacks, and the JVM's own threads (garbage collector, JIT) from
 * {@code /proc}, which no Java API shows — plus heap, GC, queues, the DB pool, NATS and the scheduled tasks. Answers
 * "is it the GC or the code" in one request. One snapshot at a time: a second one waits for the first.
 */
@Component
public class CenterSnapshot {
    private static final Logger log = LoggerFactory.getLogger(CenterSnapshot.class);
    static final Duration SAMPLE = Duration.ofSeconds(1);
    private static final int TOP = 15;
    private static final int FRAMES = 12;
    private static final Path TASKS = Path.of("/proc/self/task");
    /** Clock ticks of /proc stat times: USER_HZ is 100 on every Linux the center runs on. */
    private static final long MS_PER_TICK = 10;
    /** "   1:        123456       12345678  [B (java.base@25)" */
    private static final Pattern HISTOGRAM_LINE = Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)");

    private final HeartbeatBuffer buffer;
    private final BranchOnboarding onboarding;
    private final CenterMeters meters;
    private final CenterNats nats;
    private final DataSource dataSource;
    private final MeterRegistry registry;

    public CenterSnapshot(HeartbeatBuffer buffer, BranchOnboarding onboarding, CenterMeters meters, CenterNats nats,
                          DataSource dataSource, MeterRegistry registry) {
        this.buffer = buffer; this.onboarding = onboarding; this.meters = meters; this.nats = nats;
        this.dataSource = dataSource; this.registry = registry;
    }

    /** @param heapTop add the classes that fill the heap: a full GC, so only on request */
    public synchronized Map<String, Object> take(String by, boolean heapTop) throws InterruptedException {
        long started = System.nanoTime();
        var threadsBean = ManagementFactory.getThreadMXBean();
        var javaBefore = javaCpu(threadsBean);
        var nativeBefore = nativeCpu();
        Thread.sleep(SAMPLE.toMillis());
        var javaAfter = javaCpu(threadsBean);
        var nativeAfter = nativeCpu();

        var out = new LinkedHashMap<String, Object>();
        out.put("taken_at", java.time.Instant.now());
        out.put("taken_by", by);
        var threads = new LinkedHashMap<String, Object>();
        threads.put("sample_ms", SAMPLE.toMillis());
        threads.put("java", hotJava(threadsBean, javaBefore, javaAfter));
        threads.put("native", nativeBefore == null || nativeAfter == null
                ? "unavailable: /proc/self/task is Linux only" : hotNative(nativeBefore, nativeAfter));
        out.put("threads", threads);
        out.put("cpu", cpu());
        out.put("heap", heap());
        out.put("gc", gc());
        out.put("queues", Map.of("heartbeats_buffered", buffer.size(), "onboarding", onboarding.queued(),
                "events_pending", meters.eventsPending()));
        out.put("db_pool", pool());
        out.put("nats", nats());
        out.put("tasks", tasks());
        if (heapTop) {
            out.put("heap_top", heapTop());
        }
        log.info("center diagnostics taken by={} heap_top={} in {} ms", by, heapTop,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        return out;
    }

    private static Map<Long, Long> javaCpu(java.lang.management.ThreadMXBean bean) {
        var cpu = new HashMap<Long, Long>();
        for (long id : bean.getAllThreadIds()) {
            long ns = bean.getThreadCpuTime(id);
            if (ns >= 0) {
                cpu.put(id, ns);
            }
        }
        return cpu;
    }

    private static List<Map<String, Object>> hotJava(java.lang.management.ThreadMXBean bean, Map<Long, Long> before,
                                                     Map<Long, Long> after) {
        var deltas = new ArrayList<long[]>();
        after.forEach((id, ns) -> deltas.add(new long[]{id, ns - before.getOrDefault(id, 0L)}));
        deltas.sort(Comparator.comparingLong((long[] d) -> d[1]).reversed());
        var out = new ArrayList<Map<String, Object>>();
        for (var d : deltas.subList(0, Math.min(TOP, deltas.size()))) {
            var info = bean.getThreadInfo(d[0], FRAMES);
            if (info == null) {
                continue;                                   // ended within the second
            }
            var t = new LinkedHashMap<String, Object>();
            t.put("name", info.getThreadName());
            t.put("cpu_ms", TimeUnit.NANOSECONDS.toMillis(d[1]));
            t.put("state", info.getThreadState().name());
            t.put("stack", Stream.of(info.getStackTrace()).map(StackTraceElement::toString).toList());
            out.add(t);
        }
        return out;
    }

    /** CPU ticks of every thread of the process, by its name; {@code null} where there is no /proc. */
    private static Map<String, Long> nativeCpu() {
        if (!Files.isDirectory(TASKS)) {
            return null;
        }
        var ticks = new HashMap<String, Long>();
        try (var tasks = Files.list(TASKS)) {
            tasks.forEach(task -> {
                try {
                    var stat = Files.readString(task.resolve("stat"));
                    int open = stat.indexOf('('), close = stat.lastIndexOf(')');
                    var name = stat.substring(open + 1, close);
                    var fields = stat.substring(close + 2).split(" ");
                    long used = Long.parseLong(fields[11]) + Long.parseLong(fields[12]);    // utime + stime
                    ticks.merge(task.getFileName() + "\t" + name, used, Long::sum);
                } catch (IOException | RuntimeException e) {
                    // the thread ended between the listing and the read
                }
            });
        } catch (IOException e) {
            return null;
        }
        return ticks;
    }

    /** Busiest groups of threads: "GC Thread#3" and "GC Thread#7" are one "GC Thread". */
    private static List<Map<String, Object>> hotNative(Map<String, Long> before, Map<String, Long> after) {
        var groups = new HashMap<String, long[]>();
        after.forEach((key, used) -> {
            var name = key.substring(key.indexOf('\t') + 1).replaceAll("[#-]?\\d+$", "").strip();
            var g = groups.computeIfAbsent(name, k -> new long[2]);
            g[0] += (used - before.getOrDefault(key, 0L)) * MS_PER_TICK;
            g[1]++;
        });
        return groups.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed())
                .limit(TOP)
                .map(e -> Map.<String, Object>of("name", e.getKey(), "cpu_ms", e.getValue()[0], "threads", e.getValue()[1]))
                .toList();
    }

    /** The live classes by size after a full GC, from the JVM's own class histogram (works in a JRE, no jcmd). */
    private static Object heapTop() {
        try {
            var out = (String) ManagementFactory.getPlatformMBeanServer().invoke(
                    new ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram",
                    new Object[]{new String[0]}, new String[]{String[].class.getName()});
            var top = new ArrayList<Map<String, Object>>();
            for (var line : out.split("\n")) {
                var m = HISTOGRAM_LINE.matcher(line);
                if (m.find()) {
                    top.add(Map.of("class", m.group(3), "instances", Long.parseLong(m.group(1)),
                            "mb", Math.round(Long.parseLong(m.group(2)) / 104857.6) / 10.0));
                    if (top.size() == TOP + 5) {
                        break;
                    }
                }
            }
            return top;
        } catch (Exception e) {
            return "unavailable: " + e;
        }
    }

    private static Map<String, Object> cpu() {
        var os = ManagementFactory.getOperatingSystemMXBean();
        var m = new LinkedHashMap<String, Object>();
        m.put("cores", os.getAvailableProcessors());
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            m.put("process", round(sun.getProcessCpuLoad()));
            m.put("system", round(sun.getCpuLoad()));
        } else {
            m.put("process", null);
        }
        m.put("load_avg", round(os.getSystemLoadAverage()));
        return m;
    }

    private static Map<String, Object> heap() {
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        return Map.of("used_mb", heap.getUsed() >> 20, "committed_mb", heap.getCommitted() >> 20,
                "max_mb", heap.getMax() >> 20, "non_heap_mb", nonHeap.getUsed() >> 20);
    }

    private static List<Map<String, Object>> gc() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(g -> Map.<String, Object>of("name", g.getName(), "collections", g.getCollectionCount(),
                        "time_ms", g.getCollectionTime()))
                .toList();
    }

    private Map<String, Object> pool() {
        var m = new LinkedHashMap<String, Object>();
        if (dataSource instanceof HikariDataSource hikari && hikari.getHikariPoolMXBean() != null) {
            var p = hikari.getHikariPoolMXBean();
            m.put("active", p.getActiveConnections());
            m.put("idle", p.getIdleConnections());
            m.put("waiting", p.getThreadsAwaitingConnection());
            m.put("max", hikari.getMaximumPoolSize());
        } else {
            m.put("active", null);
        }
        return m;
    }

    private Map<String, Object> nats() {
        var m = new LinkedHashMap<String, Object>();
        m.put("state", nats.state());
        nats.connection().ifPresent(c -> {
            var s = c.getStatistics();
            m.put("server", c.getConnectedUrl());
            m.put("in_msgs", s.getInMsgs());
            m.put("out_msgs", s.getOutMsgs());
            m.put("dropped", s.getDroppedCount());
            m.put("reconnects", s.getReconnects());
        });
        return m;
    }

    /** Scheduled tasks of the center: how often each ran, how long it takes. */
    private List<Map<String, Object>> tasks() {
        return registry.find("tasks.scheduled.execution").timers().stream()
                .collect(java.util.stream.Collectors.groupingBy(t -> shortName(t.getId().getTag("code.namespace"))
                        + "." + t.getId().getTag("code.function")))
                .entrySet().stream()
                .map(e -> {
                    long count = e.getValue().stream().mapToLong(Timer::count).sum();
                    double total = e.getValue().stream().mapToDouble(t -> t.totalTime(TimeUnit.MILLISECONDS)).sum();
                    double max = e.getValue().stream().mapToDouble(t -> t.max(TimeUnit.MILLISECONDS)).max().orElse(0);
                    return Map.<String, Object>of("task", e.getKey(), "runs", count,
                            "mean_ms", count == 0 ? 0 : Math.round(total / count), "max_ms", Math.round(max));
                })
                .sorted(Comparator.comparingLong((Map<String, Object> m) -> (long) m.get("max_ms")).reversed())
                .toList();
    }

    private static String shortName(String namespace) {
        return namespace == null ? "?" : namespace.substring(namespace.lastIndexOf('.') + 1);
    }

    private static Double round(double v) {
        return v < 0 ? null : Math.round(v * 1000) / 1000.0;
    }
}
