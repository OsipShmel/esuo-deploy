package honey.road.center.network;

import honey.road.center.config.CenterProperties;
import honey.road.center.monitoring.BranchAlerts;
import honey.road.contracts.error.ErrorCode;
import honey.road.contracts.error.EsuoException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Network overview (sect 9.5, 14; scale design 8.2). Lists, counts and filters come from {@link NetworkSnapshot}, one
 * picture of the network shared by every screen; cards — only those shown — are read fresh from the database, so a
 * script waiting for a branch to go off line sees it at once. {@code /network/overview} returns every card and is
 * refused above {@code overview-max} branches: on a big network it took the center down.
 * Role of NETWORK_MONITOR — TODO! after the hub security component;
 */
@RestController
public class NetworkController {
    static final int MAX_TOP = 100;
    static final int MAX_PAGE = 200;
    static final int PUBLIC_PAGE = 50;
    static final int REGION_WORST = 10;

    private final BranchRegistry registry;
    private final BranchAlerts alerts;
    private final NetworkSnapshot snapshot;
    private final CenterProperties props;
    private final JsonMapper json;
    private final Clock clock;

    public NetworkController(BranchRegistry registry, BranchAlerts alerts, NetworkSnapshot snapshot, CenterProperties props,
                             JsonMapper json, Clock clock) {
        this.registry = registry; this.alerts = alerts; this.snapshot = snapshot; this.props = props; this.json = json;
        this.clock = clock;
    }

    /**
     * Public branch list for the client's picker (with the service catalog from heartbeat – вот бы хоть раз сходит на
     * почту и узнать, как она работает): searched and paged by the shared picture of the network, so an anonymous
     * request costs the center no more than that picture. {@code limit} 1…{@value #MAX_PAGE}.
     */
    @GetMapping("/api/v1/branches")
    public Map<String, Object> branches(@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int offset,
                                        @RequestParam(defaultValue = "" + PUBLIC_PAGE) int limit) {
        var now = clock.instant();
        var matched = snapshot.current().select(q, null, null, null);
        var ids = slice(matched, offset, limit).stream().map(NetworkSnapshot.Entry::id).toList();
        var rows = new HashMap<String, BranchRegistry.BranchRow>();
        registry.rows(ids).forEach(r -> rows.put(r.id(), r));
        var items = new ArrayList<Map<String, Object>>();
        for (var id : ids) {
            var b = rows.get(id);
            if (b == null) {
                continue;
            }
            var hb = json.readTree(b.lastHeartbeat());
            var m = new LinkedHashMap<String, Object>();
            m.put("id", b.id());
            m.put("name", b.name());
            m.put("timezone", b.timezone());
            m.put("online", NetworkAnomalies.online(b.lastHeartbeatAt(), now, props.offlineAfter()));
            m.put("services", hb.path("services"));
            m.put("load_level", hb.path("load_level").asString(null));
            items.add(m);
        }
        return pageOf(now, matched.size(), offset, limit, items);
    }

    /** Every card at once — for the scripts of a small stand; refused above {@code overview-max} branches. */
    @GetMapping("/api/v1/network/overview")
    public Map<String, Object> overview() {
        int max = props.network().overviewMax();
        if (registry.countUpTo(max + 1) > max) {
            throw new EsuoException(ErrorCode.NETWORK_TOO_LARGE, "Больше " + max
                    + " отделений: используйте /api/v1/network/summary и /api/v1/network/branches");
        }
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
     * Network summary (C3c, scale design 8.1): counts by status, load level and anomaly, a tile per region, and the
     * {@code top} worst branches as full cards. {@code top}: 1…{@value #MAX_TOP}.
     */
    @GetMapping("/api/v1/network/summary")
    public Map<String, Object> summary(@RequestParam(defaultValue = "20") int top) {
        var v = snapshot.current();
        var result = new LinkedHashMap<String, Object>();
        result.put("generated_at", v.generatedAt());
        result.put("branches_total", v.network().total());
        result.put("branches_online", v.network().online());
        result.put("branches_offline", v.network().total() - v.network().online());
        result.put("problem_branches_total", v.network().problems());
        result.put("load_levels", v.network().loadLevels());
        result.put("anomalies", v.network().anomalies());
        result.put("regions", v.regions().entrySet().stream()
                .map(e -> e.getValue().view(NetworkSnapshot.OWN.equals(e.getKey()) ? null : e.getKey())).toList());
        result.put("problems", cards(v.problems().subList(0, Math.min(v.problems().size(), Math.clamp(top, 1, MAX_TOP)))));
        return result;
    }

    /** One region (scale design 8.1): its tile and its {@value #REGION_WORST} worst branches as cards. */
    @GetMapping("/api/v1/network/regions/{region}")
    public Map<String, Object> region(@PathVariable String region) {
        var v = snapshot.current();
        var sums = v.regions().get(region);
        if (sums == null) {
            throw new EsuoException(ErrorCode.NOT_FOUND, "Региона " + region + " нет");
        }
        var result = new LinkedHashMap<String, Object>(sums.view(NetworkSnapshot.OWN.equals(region) ? null : region));
        result.put("generated_at", v.generatedAt());
        result.put("problems", cards(v.select(null, "problems", region, null).stream().limit(REGION_WORST).toList()));
        return result;
    }

    /**
     * One page of branch cards. {@code q} — part of the id or the name, any case; {@code status} — all, online,
     * offline or problems; {@code region} — {@code events_via} or {@value NetworkSnapshot#OWN}; {@code anomaly} — its
     * code. Problems and anomalies come worst first, the rest by id. {@code limit} 1…{@value #MAX_PAGE}.
     */
    @GetMapping("/api/v1/network/branches")
    public Map<String, Object> page(@RequestParam(required = false) String q, @RequestParam(defaultValue = "all") String status,
                                    @RequestParam(required = false) String region, @RequestParam(required = false) String anomaly,
                                    @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit) {
        var v = snapshot.current();
        var matched = v.select(q, status, region, anomaly);
        return pageOf(v.generatedAt(), matched.size(), offset, limit, cards(slice(matched, offset, limit)));
    }

    /** One branch card, fresh: the panel of a branch. */
    @GetMapping("/api/v1/network/branches/{id}")
    public Map<String, Object> branch(@PathVariable String id) {
        var cards = cardsOf(List.of(id));
        if (cards.isEmpty()) {
            throw new EsuoException(ErrorCode.BRANCH_NOT_FOUND, "Отделение " + id + " не зарегистрировано");
        }
        return cards.getFirst();
    }

    private static <T> List<T> slice(List<T> matched, int offset, int limit) {
        int from = Math.clamp(offset, 0, matched.size());
        return matched.subList(from, Math.min(matched.size(), from + Math.clamp(limit, 1, MAX_PAGE)));
    }

    private static Map<String, Object> pageOf(Instant generatedAt, int total, int offset, int limit, List<?> items) {
        var result = new LinkedHashMap<String, Object>();
        result.put("generated_at", generatedAt);
        result.put("total", total);
        result.put("offset", Math.clamp(offset, 0, total));
        result.put("limit", Math.clamp(limit, 1, MAX_PAGE));
        result.put("items", items);
        return result;
    }

    private List<Map<String, Object>> cards(List<NetworkSnapshot.Entry> entries) {
        return cardsOf(entries.stream().map(NetworkSnapshot.Entry::id).toList());
    }

    /** Full cards of a few branches, in the order given, from their rows and alerts read now. */
    private List<Map<String, Object>> cardsOf(List<String> ids) {
        var now = clock.instant();
        var rows = new HashMap<String, BranchRegistry.BranchRow>();
        registry.rows(ids).forEach(r -> rows.put(r.id(), r));
        var alertsByBranch = alerts.forBranches(ids);
        var out = new ArrayList<Map<String, Object>>(ids.size());
        for (var id : ids) {
            var row = rows.get(id);
            if (row != null) { out.add(card(row, alertsByBranch.getOrDefault(id, List.of()), now)); }
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
