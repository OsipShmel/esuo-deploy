package honey.road.center.network;

import honey.road.center.config.CenterProperties;
import honey.road.center.monitoring.BranchAlerts;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Network overview (sect 9.5, 14): online/offline by heartbeat, queue, outbox lag, config versions and anomalies.
 * {@code /network/overview} returns every card (scripts read it); a big network uses {@code /network/summary} and
 * the paged {@code /network/branches} instead (final plan, C3c).
 * Role of NETWORK_MONITOR — TODO! after the hub security component;
 */
@RestController
public class NetworkController {
    private final BranchRegistry registry;
    private final BranchAlerts alerts;
    private final CenterProperties props;
    private final JsonMapper json;
    private final Clock clock;

    public NetworkController(BranchRegistry registry, BranchAlerts alerts, CenterProperties props, JsonMapper json,
                             Clock clock) {
        this.registry = registry; this.alerts = alerts; this.props = props; this.json = json; this.clock = clock;
    }

    /** Public branch list for the client (with the service catalog from heartbeat –
     * вот бы хоть раз сходит на почту и узнать, как она работает). */
    @GetMapping("/api/v1/branches")
    public List<Map<String, Object>> branches() {
        var now = clock.instant();
        var result = new ArrayList<Map<String, Object>>();
        for (var b : registry.all()) {
            var hb = json.readTree(b.lastHeartbeat());
            var m = new LinkedHashMap<String, Object>();
            m.put("id", b.id());
            m.put("name", b.name());
            m.put("timezone", b.timezone());
            m.put("online", NetworkAnomalies.online(b.lastHeartbeatAt(), now, props.offlineAfter()));
            m.put("services", hb.path("services"));
            m.put("load_level", hb.path("load_level").asString(null));
            result.add(m);
        }
        return result;
    }

    @GetMapping("/api/v1/network/overview")
    public Map<String, Object> overview() {
        var now = clock.instant();
        var alertsByBranch = alerts.byBranch();
        var branches = new ArrayList<Map<String, Object>>();
        int online = 0;
        for (var b : registry.all()) {
            var c = card(b, alertsByBranch.getOrDefault(b.id(), List.of()), now);
            if (Boolean.TRUE.equals(c.get("online"))) { online++; }
            branches.add(c);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("generated_at", now);
        result.put("branches_total", branches.size());
        result.put("branches_online", online);
        result.put("branches", branches);
        return result;
    }

    /**
     * Network summary for a big network (final plan, C3c): counts by status, load level, anomaly and region, plus the
     * {@code top} worst branches as full cards. Judged from a dozen fields the database extracts from each heartbeat:
     * 40 000 branches cost a few megabytes per poll, not every card. {@code top}: 1…{@value #MAX_TOP}.
     */
    @GetMapping("/api/v1/network/summary")
    public Map<String, Object> summary(@RequestParam(defaultValue = "20") int top) {
        var now = clock.instant();
        var alertsByBranch = alerts.byBranch();
        var judged = judge(registry.statuses(), alertsByBranch, now);
        int online = 0;
        var loadLevels = new TreeMap<String, Integer>();
        var anomalies = new TreeMap<String, Integer>();
        var regions = new LinkedHashMap<String, int[]>();               // events_via → total, online, problems
        var problems = new ArrayList<Judged>();
        for (var j : judged) {
            if (j.online) { online++; }
            loadLevels.merge(j.status.loadLevel() == null ? "unknown" : j.status.loadLevel(), 1, Integer::sum);
            j.anomalies.forEach(a -> anomalies.merge(a, 1, Integer::sum));
            var r = regions.computeIfAbsent(j.status.eventsVia() == null ? OWN_LEAF : j.status.eventsVia(), k -> new int[3]);
            r[0]++;
            if (j.online) { r[1]++; }
            if (!j.anomalies.isEmpty()) { r[2]++; problems.add(j); }
        }
        problems.sort(WORST_FIRST);
        var shown = problems.subList(0, Math.min(problems.size(), Math.clamp(top, 1, MAX_TOP)));
        var result = new LinkedHashMap<String, Object>();
        result.put("generated_at", now);
        result.put("branches_total", judged.size());
        result.put("branches_online", online);
        result.put("branches_offline", judged.size() - online);
        result.put("problem_branches_total", problems.size());
        result.put("load_levels", loadLevels);
        result.put("anomalies", anomalies);
        var regionList = new ArrayList<Map<String, Object>>();
        regions.forEach((via, r) -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("events_via", OWN_LEAF.equals(via) ? null : via);
            m.put("total", r[0]);
            m.put("online", r[1]);
            m.put("problems", r[2]);
            regionList.add(m);
        });
        result.put("regions", regionList);
        result.put("problems", cards(shown, alertsByBranch, now));
        return result;
    }

    /**
     * One page of branch cards (C3c). {@code q} — part of the id or the name, any case; {@code status} — all, online,
     * offline or problems (worst first; otherwise by id); {@code limit} 1…{@value #MAX_PAGE}.
     */
    @GetMapping("/api/v1/network/branches")
    public Map<String, Object> page(@RequestParam(required = false) String q, @RequestParam(defaultValue = "all") String status,
                                    @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit) {
        var now = clock.instant();
        var alertsByBranch = alerts.byBranch();
        var needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        var matched = new ArrayList<Judged>();
        for (var j : judge(registry.statuses(), alertsByBranch, now)) {
            if (!needle.isEmpty() && !j.status.id().toLowerCase(Locale.ROOT).contains(needle)
                    && !j.status.name().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            boolean keep = switch (status) {
                case "online" -> j.online;
                case "offline" -> !j.online;
                case "problems" -> !j.anomalies.isEmpty();
                default -> true;
            };
            if (keep) { matched.add(j); }
        }
        if ("problems".equals(status)) { matched.sort(WORST_FIRST); }
        int size = Math.clamp(limit, 1, MAX_PAGE);
        int from = Math.clamp(offset, 0, matched.size());
        var result = new LinkedHashMap<String, Object>();
        result.put("generated_at", now);
        result.put("total", matched.size());
        result.put("offset", from);
        result.put("limit", size);
        result.put("items", cards(matched.subList(from, Math.min(matched.size(), from + size)), alertsByBranch, now));
        return result;
    }

    static final int MAX_TOP = 100;
    static final int MAX_PAGE = 200;
    private static final String OWN_LEAF = "";

    /** A branch with its verdict: online, anomaly codes, severity. */
    private record Judged(BranchStatus status, boolean online, List<String> anomalies, int severity) {
    }

    private static final Comparator<Judged> WORST_FIRST =
            Comparator.comparingInt(Judged::severity).reversed().thenComparing(j -> j.status().id());

    private List<Judged> judge(List<BranchStatus> statuses, Map<String, List<Map<String, Object>>> alertsByBranch, Instant now) {
        var out = new ArrayList<Judged>(statuses.size());
        for (var s : statuses) {
            var branchAlerts = alertsByBranch.getOrDefault(s.id(), List.of());
            var anomalies = NetworkAnomalies.of(s, branchAlerts, now, props.offlineAfter());
            out.add(new Judged(s, NetworkAnomalies.online(s.lastHeartbeatAt(), now, props.offlineAfter()), anomalies,
                    NetworkAnomalies.severity(anomalies, branchAlerts, s.overdue())));
        }
        return out;
    }

    /** Full cards of a few judged branches, in their order. */
    private List<Map<String, Object>> cards(List<Judged> judged, Map<String, List<Map<String, Object>>> alertsByBranch, Instant now) {
        var rows = new HashMap<String, BranchRegistry.BranchRow>();
        registry.rows(judged.stream().map(j -> j.status().id()).toList()).forEach(r -> rows.put(r.id(), r));
        var out = new ArrayList<Map<String, Object>>(judged.size());
        for (var j : judged) {
            var row = rows.get(j.status().id());
            if (row != null) { out.add(card(row, alertsByBranch.getOrDefault(row.id(), List.of()), now)); }
        }
        return out;
    }

    /** One branch card of the overview, from its row and last heartbeat. */
    private Map<String, Object> card(BranchRegistry.BranchRow b, List<Map<String, Object>> branchAlerts, Instant now) {
        JsonNode hb = json.readTree(b.lastHeartbeat());
        var status = status(b, hb);
        boolean isOnline = NetworkAnomalies.online(b.lastHeartbeatAt(), now, props.offlineAfter());
        var anomalies = NetworkAnomalies.of(status, branchAlerts, now, props.offlineAfter());
        boolean dbStuck = isOnline && !status.dbOk();
        int applied = Math.max(status.hbConfigVersion(), b.configAppliedVersion() == null ? 0 : b.configAppliedVersion());
        var m = new LinkedHashMap<String, Object>();
        m.put("id", b.id());
        m.put("name", b.name());
        m.put("online", isOnline);
        m.put("last_heartbeat_at", b.lastHeartbeatAt());
        m.put("seconds_since_heartbeat", Duration.between(b.lastHeartbeatAt(), now).toSeconds());
        m.put("events_attached", b.sourceAttached());
        m.put("events_via", status.eventsVia());
        m.put("waiting", status.waiting());
        m.put("overdue", status.overdue());
        m.put("oldest_wait_sec", status.oldestWaitSec());
        m.put("windows_open", status.windowsOpen());
        m.put("windows_total", hb.path("windows_total").asLong(0));
        m.put("outbox_pending", hb.path("outbox_pending").asLong(0));
        m.put("config_version", status.hbConfigVersion());
        m.put("effective_config_version", hb.path("effective_config_version").asInt(0));
        m.put("config_published_version", b.configPublishedVersion());
        m.put("config_applied_version", applied == 0 ? null : applied);
        m.put("load_level", status.loadLevel());
        m.put("clear_time_min", hb.hasNonNull("clear_time_min") ? hb.path("clear_time_min").asInt() : null);
        m.put("day_profile", hb.path("day_profile").asString(null));
        m.put("demo_rate", hb.hasNonNull("demo_rate") ? hb.path("demo_rate").asDouble() : null);
        m.put("db_unavailable_sec", dbStuck ? hb.path("db_unavailable_sec").asLong(0) : null);
        m.put("anomalies", anomalies);
        m.put("alerts", branchAlerts);
        m.put("node", nodeLine(hb.path("sys")));
        m.put("dashboard_url", dashboardUrl(b.id()));
        return m;
    }

    /** The same fields the database extracts for {@link BranchRegistry#statuses()}, read from the heartbeat here. */
    private static BranchStatus status(BranchRegistry.BranchRow b, JsonNode hb) {
        return new BranchStatus(b.id(), b.name(), b.lastHeartbeatAt(), b.sourceAttached(),
                hb.path("waiting").asLong(0), hb.path("overdue").asLong(0), hb.path("oldest_wait_sec").asLong(0),
                hb.path("windows_open").asLong(0), hb.path("outbox_lag_sec").asLong(0), hb.path("load_level").asString(null),
                NetworkAnomalies.instant(hb.path("load_level_since").asString(null)),
                NetworkAnomalies.instant(hb.path("sent_at").asString(null)), hb.path("db_ok").asBoolean(true),
                hb.path("config_version").asInt(0), b.configPublishedVersion(), b.configPublishedAt(), b.configAppliedVersion(),
                hb.path("events_via").asString(null));
    }

    /** The «Узел» line of the card (B7): a few numbers of the last heartbeat; the rest is on the Grafana dashboard. */
    private static Map<String, Object> nodeLine(JsonNode sys) {
        if (!sys.isObject()) {
            return null;
        }
        var m = new LinkedHashMap<String, Object>();
        m.put("cpu_pct", sys.hasNonNull("cpu_system") ? (Object) Math.round(sys.path("cpu_system").asDouble() * 100) : null);
        m.put("http_p95_ms", sys.hasNonNull("http_p95_ms") ? sys.path("http_p95_ms").asLong() : null);
        m.put("disk_free_mb", sys.hasNonNull("disk_free_mb") ? sys.path("disk_free_mb").asLong() : null);
        m.put("disk_total_mb", sys.hasNonNull("disk_total_mb") ? sys.path("disk_total_mb").asLong() : null);
        m.put("app_version", sys.path("app_version").asString(null));
        m.put("uptime_sec", sys.hasNonNull("uptime_sec") ? sys.path("uptime_sec").asLong() : null);
        return m;
    }

    /** Dashboard «ЕСУО · Отделение» (uid {@code esuo-branch}) opened on this branch; none without a public Grafana URL. */
    private String dashboardUrl(String branchId) {
        var grafana = props.logs().grafanaPublicUrl();
        return grafana.isEmpty() ? null : grafana + "/d/esuo-branch/?var-branch=" + URLEncoder.encode(branchId, StandardCharsets.UTF_8);
    }
}
