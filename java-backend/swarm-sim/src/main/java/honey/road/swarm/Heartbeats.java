package honey.road.swarm;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Heartbeat of a virtual branch: the fields of the node's {@code HeartbeatPublisher.snapshot()} plus
 * {@code events_via} (its events are in the regional {@code REGION_EVT}, not in a branch {@code BRANCH_EVT}) and the
 * {@code sys} block of the final plan (B1) with plausible values, so the center gets realistically sized messages.
 */
final class Heartbeats {
    static final long OVERDUE_AFTER_MS = 20 * Timeline.MINUTE;

    private Heartbeats() { }

    static Map<String, Object> snapshot(VirtualBranch b, Instant virtualNow, Instant realNow, double speed) {
        long now = virtualNow.toEpochMilli();
        var hb = new LinkedHashMap<String, Object>();
        hb.put("branch_id", b.id);
        hb.put("name", b.name);
        hb.put("timezone", b.zone.getId());
        hb.put("sent_at", virtualNow);
        if (speed != 1) { hb.put("demo_rate", speed); }   // like DemoClock: the center converts TTLs to branch time
        hb.put("config_version", b.configVersion);
        hb.put("effective_config_version", b.configVersion);
        hb.put("outbox_pending", 0);
        hb.put("outbox_lag_sec", 0);
        hb.put("windows_total", b.windowIds.length);
        hb.put("windows_open", b.windowsOpen());
        hb.put("waiting", b.waiting.size());
        long overdue = 0;
        for (long issued : b.waiting) { if (now - issued >= OVERDUE_AFTER_MS) { overdue++; } }
        hb.put("overdue", overdue);
        hb.put("oldest_wait_sec", b.waiting.isEmpty() ? 0 : Math.max(0, (now - b.waiting.peekFirst()) / 1000));
        int load = b.windowsOpen() == 0 ? 0 : b.waiting.size() / Math.max(1, b.windowsOpen());
        hb.put("load_level", load >= 8 ? "overload" : load >= 4 ? "high" : "normal");
        hb.put("load_level_since", b.startedAt);
        hb.put("day_profile", b.profile.code());
        hb.put("services", services());
        hb.put("events_via", b.eventsVia);
        hb.put("sys", sys(b, realNow, load));
        return hb;
    }

    private static List<Map<String, Object>> services() {
        return java.util.Arrays.stream(Services.ALL)
                .map(s -> Map.<String, Object>of("id", s.id(), "code", s.code(), "name", s.name(), "number_prefix", s.prefix()))
                .toList();
    }

    /** B1 fields; values drift with the queue so dashboards and rules have something to react to. */
    private static Map<String, Object> sys(VirtualBranch b, Instant realNow, int load) {
        var r = java.util.concurrent.ThreadLocalRandom.current();     // not b.rng: heartbeats must not shift the flow model
        double busy = Math.min(1, 0.05 + load * 0.06 + r.nextDouble() * 0.05);
        var sys = new LinkedHashMap<String, Object>();
        sys.put("cpu_process", round(busy * 0.6));
        sys.put("cpu_system", round(busy));
        sys.put("load_avg", round(busy * 4));
        sys.put("heap_used_mb", 180 + r.nextInt(120));
        sys.put("heap_max_mb", 512);
        sys.put("gc_pause_ms", r.nextInt(15));
        sys.put("db_pool_active", Math.min(10, load / 2 + r.nextInt(2)));
        sys.put("db_pool_pending", 0);
        sys.put("db_pool_max", 10);
        sys.put("db_size_mb", 40 + b.eventsEmitted / 2000);
        sys.put("disk_free_mb", 180_000 - b.eventsEmitted / 100);
        sys.put("disk_total_mb", 238_000);
        sys.put("http_rps", round(0.5 + load * 0.4 + r.nextDouble()));
        sys.put("http_p95_ms", 25 + load * 6 + r.nextInt(20));
        sys.put("http_5xx_rate", 0.0);
        sys.put("sse_clients", b.waiting.size() + b.windowsOpen() + 1);
        sys.put("leaf_connected", true);
        sys.put("leaf_rtt_ms", 2 + r.nextInt(40));
        sys.put("nats_reconnects", 0);
        sys.put("sent_at_system", realNow);
        sys.put("uptime_sec", Duration.between(b.startedAt, realNow).toSeconds());
        sys.put("started_at", b.startedAt);
        sys.put("app_version", "swarm-sim");
        return sys;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
