package honey.road.center.network;

import honey.road.center.config.CenterProperties;
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
 * Обзор сети (раздел 14, сетевой мониторинг) [MVP-демо]: online/offline по heartbeat, очередь, лаг outbox,
 * аномалия «ожидание растёт, а талонов мало» (слайд мониторинга). Роль NETWORK_MONITOR — [TODO] после
 * безопасности центра; данные без персональной информации.
 */
@RestController
public class NetworkController {
    private final BranchRegistry registry;
    private final CenterProperties props;
    private final JsonMapper json;
    private final Clock clock;

    public NetworkController(BranchRegistry registry, CenterProperties props, JsonMapper json, Clock clock) {
        this.registry = registry; this.props = props; this.json = json; this.clock = clock;
    }

    /** Публичный список отделений для выбора клиентом (с каталогом услуг из heartbeat). */
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
            result.add(m);
        }
        return result;
    }

    @GetMapping("/api/v1/network/overview")
    public Map<String, Object> overview() {
        var now = clock.instant();
        var branches = new ArrayList<Map<String, Object>>();
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
            m.put("config_version", hb.path("config_version").asInt(0));
            m.put("anomalies", anomalies);
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
