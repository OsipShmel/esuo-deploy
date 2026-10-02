package honey.road.center.network;

import honey.road.center.config.CenterProperties;
import honey.road.center.monitoring.BranchAlerts;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Network overview (sect 9.5, 14): online/offline by heartbeat, queue, outbox lag, config versions and anomalies.
 * Role of NETWORK_MONITOR — TODO! after the hub security component;
 */
@RestController
public class NetworkController {
    /** Published config not applied for longer than this while online → {@code config_pending} (sect 9.5). */
    static final Duration CONFIG_PENDING_AFTER = Duration.ofMinutes(2);

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
            m.put("online", online(b.lastHeartbeatAt(), now));
            m.put("services", hb.path("services"));
            m.put("load_level", hb.path("load_level").asString(null));
            result.add(m);
        }
        return result;
    }

    /** Сколько отделение должно провисеть в перегрузке, чтобы это стало аномалией (load-planning.md 6.2). */
    private static final Duration OVERLOAD_ANOMALY_AFTER = Duration.ofMinutes(15);

    /**
     * A branch stuck in {@code overload} longer than fifteen minutes (load-planning.md 6.2).
     *
     * <p>The moment the level was entered comes from the branch itself ({@code load_level_since}) rather than
     * being counted here from a run of heartbeats: a link outage interrupts that run, and the branch keeps
     * working and keeps its own level through it — so counting centrally would reset the clock exactly when the
     * branch is worst off. A branch that does not send the field yet simply never raises this anomaly.
     *
     * <p>The branch clock may be a demo clock running at speed, so the comparison uses the branch's own
     * {@code sent_at} rather than the center's wall clock; falling back to the center's only when it is absent.
     */
    private boolean overloadedFor(JsonNode hb, Instant now) {
        if (!"overload".equals(hb.path("load_level").asString(null))) {
            return false;
        }
        var since = instant(hb.path("load_level_since").asString(null));
        if (since == null) {
            return false;
        }
        var reference = instant(hb.path("sent_at").asString(null));
        return Duration.between(since, reference == null ? now : reference).compareTo(OVERLOAD_ANOMALY_AFTER) > 0;
    }

    private static Instant instant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @GetMapping("/api/v1/network/overview")
    public Map<String, Object> overview() {
        var now = clock.instant();
        var branches = new ArrayList<Map<String, Object>>();
        var alertsByBranch = alerts.byBranch();
        int online = 0;
        for (var b : registry.all()) {
            JsonNode hb = json.readTree(b.lastHeartbeat());
            boolean isOnline = online(b.lastHeartbeatAt(), now);
            if (isOnline) { online++; }
            var anomalies = new ArrayList<String>();
            long waiting = hb.path("waiting").asLong(0);
            long oldestWait = hb.path("oldest_wait_sec").asLong(0);
            long overdue = hb.path("overdue").asLong(0);
            if (!isOnline) { anomalies.add("offline"); }
            if (oldestWait > 1800 && waiting <= 3) { anomalies.add("wait_grows_without_tickets"); }
            if (overdue > 5) { anomalies.add("overdue_tickets"); }
            if (hb.path("windows_open").asLong(0) == 0 && waiting > 0) { anomalies.add("no_open_windows"); }
            if (hb.path("outbox_lag_sec").asLong(0) > 60) { anomalies.add("outbox_lag"); }
            if (isOnline && overloadedFor(hb, now)) { anomalies.add("overload"); }
            int hbConfig = hb.path("config_version").asInt(0);
            Integer published = b.configPublishedVersion();
            int applied = Math.max(hbConfig, b.configAppliedVersion() == null ? 0 : b.configAppliedVersion());
            if (isOnline && published != null && applied < published && b.configPublishedAt() != null
                    && Duration.between(b.configPublishedAt(), now).compareTo(CONFIG_PENDING_AFTER) > 0) {
                anomalies.add("config_pending");
            }
            // monitoring rules (design 6.4): the code joins the anomalies, the text is ready for a person
            var branchAlerts = alertsByBranch.getOrDefault(b.id(), List.of());
            branchAlerts.forEach(a -> anomalies.add((String) a.get("code")));
            var m = new LinkedHashMap<String, Object>();
            m.put("id", b.id());
            m.put("name", b.name());
            m.put("online", isOnline);
            m.put("last_heartbeat_at", b.lastHeartbeatAt());
            m.put("seconds_since_heartbeat", Duration.between(b.lastHeartbeatAt(), now).toSeconds());
            m.put("events_attached", b.sourceAttached());
            m.put("waiting", waiting);
            m.put("overdue", overdue);
            m.put("oldest_wait_sec", oldestWait);
            m.put("windows_open", hb.path("windows_open").asLong(0));
            m.put("windows_total", hb.path("windows_total").asLong(0));
            m.put("outbox_pending", hb.path("outbox_pending").asLong(0));
            m.put("config_version", hbConfig);
            m.put("effective_config_version", hb.path("effective_config_version").asInt(0));
            m.put("config_published_version", published);
            m.put("config_applied_version", applied == 0 ? null : applied);
            m.put("load_level", hb.path("load_level").asString(null));
            m.put("clear_time_min", hb.hasNonNull("clear_time_min") ? hb.path("clear_time_min").asInt() : null);
            m.put("day_profile", hb.path("day_profile").asString(null));
            m.put("demo_rate", hb.hasNonNull("demo_rate") ? hb.path("demo_rate").asDouble() : null);
            m.put("anomalies", anomalies);
            m.put("alerts", branchAlerts);
            branches.add(m);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("generated_at", now);
        result.put("branches_total", branches.size());
        result.put("branches_online", online);
        result.put("branches", branches);
        return result;
    }

    private boolean online(Instant lastHeartbeat, Instant now) {
        return Duration.between(lastHeartbeat, now).compareTo(props.offlineAfter()) <= 0;
    }
}
