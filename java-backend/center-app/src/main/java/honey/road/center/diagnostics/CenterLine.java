package honey.road.center.diagnostics;

import honey.road.center.config.CenterProperties;
import honey.road.center.monitoring.CenterMeters;
import honey.road.center.network.HeartbeatBuffer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The «Центр» line of the network screen (scale design 8.1): is the center itself keeping up — CPU, garbage collector,
 * heap, heartbeats and events per second, how long a flush and a rule pass take, what waits in memory. From the
 * center's own meters, no Prometheus needed. Rates are over the time since the previous call (at least a second apart:
 * every open tab polls it).
 */
@Component
public class CenterLine {
    private static final long MIN_INTERVAL_NS = TimeUnit.SECONDS.toNanos(1);

    private final MeterRegistry meters;
    private final HeartbeatBuffer buffer;
    private final CenterMeters centerMeters;
    private final @Nullable String grafana;
    private @Nullable Sample last;
    private @Nullable Map<String, Object> lastLine;

    private record Sample(long atNs, double heartbeats, double events, long gcMs) {
    }

    public CenterLine(MeterRegistry meters, HeartbeatBuffer buffer, CenterMeters centerMeters, CenterProperties props) {
        this.meters = meters; this.buffer = buffer; this.centerMeters = centerMeters;
        var url = props.logs().grafanaPublicUrl();
        this.grafana = url.isEmpty() ? null : url + "/d/esuo-center/";
    }

    public synchronized Map<String, Object> now() {
        var sample = new Sample(System.nanoTime(), count("esuo.heartbeats.received"), count("esuo.events.ingested"), gcMs());
        if (last != null && lastLine != null && sample.atNs() - last.atNs() < MIN_INTERVAL_NS) {
            return lastLine;
        }
        var from = last != null ? last : new Sample(sample.atNs() - uptimeNs(), 0, 0, 0);
        double seconds = Math.max(1e-3, (sample.atNs() - from.atNs()) / 1e9);
        var os = ManagementFactory.getOperatingSystemMXBean();
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var m = new LinkedHashMap<String, Object>();
        m.put("cpu", os instanceof com.sun.management.OperatingSystemMXBean sun && sun.getProcessCpuLoad() >= 0
                ? Math.round(sun.getProcessCpuLoad() * os.getAvailableProcessors() * 100) : null);   // % of one core, like top
        m.put("cores", os.getAvailableProcessors());
        m.put("gc_pct", Math.round((sample.gcMs() - from.gcMs()) / 10.0 / seconds * 10) / 10.0);
        m.put("heap_used_mb", heap.getUsed() >> 20);
        m.put("heap_max_mb", heap.getMax() >> 20);
        m.put("heartbeats_per_sec", Math.round((sample.heartbeats() - from.heartbeats()) / seconds));
        m.put("events_per_sec", Math.round((sample.events() - from.events()) / seconds));
        m.put("flush_sec", maxSeconds("esuo.heartbeat.flush"));
        m.put("rules_sec", maxSeconds("esuo.rules.pass"));
        m.put("snapshot_sec", maxSeconds("esuo.network.snapshot"));
        m.put("heartbeats_buffered", buffer.size());
        m.put("events_pending", centerMeters.eventsPending());
        m.put("grafana_url", grafana);
        last = sample;
        lastLine = m;
        return m;
    }

    private double count(String counter) {
        var c = Search.in(meters).name(counter).counter();
        return c == null ? 0 : c.count();
    }

    /** The longest of the last few minutes (the timer's decaying max), seconds with one decimal. */
    private double maxSeconds(String timer) {
        var t = Search.in(meters).name(timer).timer();
        return t == null ? 0 : Math.round(t.max(TimeUnit.MILLISECONDS) / 100) / 10.0;
    }

    /** Time the collector stopped the application: pauses only, not the concurrent cycles running beside it. */
    private static long gcMs() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .filter(g -> !g.getName().contains("Concurrent") && !g.getName().contains("Cycles"))
                .mapToLong(g -> Math.max(0, g.getCollectionTime())).sum();
    }

    private static long uptimeNs() {
        return TimeUnit.MILLISECONDS.toNanos(ManagementFactory.getRuntimeMXBean().getUptime());
    }
}
