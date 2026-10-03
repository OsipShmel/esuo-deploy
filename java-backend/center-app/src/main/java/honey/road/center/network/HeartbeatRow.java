package honey.road.center.network;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * One heartbeat as the registry stores it: the whole body for {@code branches.last_heartbeat}, plus what the flush
 * needs on its own — name, time zone, {@code events_via} — and the metrics split into {@code sys} and {@code queue}.
 * Fields are only ever added to the heartbeat; whatever is missing here falls back like it did before.
 */
record HeartbeatRow(String branchId, String name, String timezone, String json, Instant receivedAt,
                    @Nullable String eventsVia, @Nullable String sys, String queue) {

    /** Queue fields kept in {@code branch_metrics.queue}: enough for the "Branch" dashboard without the catalog. */
    static final List<String> QUEUE_FIELDS = List.of("waiting", "overdue", "oldest_wait_sec", "windows_open",
            "windows_total", "outbox_pending", "outbox_lag_sec", "load_level", "clear_time_min", "config_version");

    static HeartbeatRow parse(JsonMapper json, HeartbeatBuffer.Received r) {
        var body = new String(r.body(), StandardCharsets.UTF_8);
        JsonNode hb = json.readTree(body);
        ObjectNode queue = json.createObjectNode();
        for (var field : QUEUE_FIELDS) {
            if (hb.has(field)) { queue.set(field, hb.get(field)); }
        }
        var sys = hb.path("sys");
        return new HeartbeatRow(r.branchId(), hb.path("name").asString(r.branchId()),
                hb.path("timezone").asString("Europe/Moscow"), body, r.receivedAt(),
                EventsVia.of(hb.path("events_via").asString(null)),
                sys.isObject() ? json.writeValueAsString(sys) : null, json.writeValueAsString(queue));
    }
}
