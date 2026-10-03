package honey.road.center.network;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Raw branch metrics from heartbeats (final plan, B2): {@code sys} — the node's server, {@code queue} — its queue and
 * windows, one point a minute for the last 48 h. Grafana reads the table directly; this is for the UI and scripts.
 */
@RestController
class MetricsController {
    static final Duration DEFAULT_RANGE = Duration.ofHours(1);
    static final Duration MAX_RANGE = Duration.ofHours(48);
    static final int LIMIT = 5000;

    private final BranchRegistry registry;
    private final JsonMapper json;
    private final Clock clock;

    MetricsController(BranchRegistry registry, JsonMapper json, Clock clock) {
        this.registry = registry; this.json = json; this.clock = clock;
    }

    /** {@code from}/{@code to} — ISO-8601; by default the last hour; at most 48 h and 5 000 points. */
    @GetMapping("/api/v1/branches/{b}/metrics")
    Map<String, Object> metrics(@PathVariable String b,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        var end = to == null ? clock.instant() : to;
        var start = from == null ? end.minus(DEFAULT_RANGE) : from;
        if (Duration.between(start, end).compareTo(MAX_RANGE) > 0) { start = end.minus(MAX_RANGE); }
        List<Map<String, Object>> points = registry.metrics(b, start, end, LIMIT).stream().map(r -> {
            var p = new LinkedHashMap<String, Object>();
            p.put("ts", r.ts());
            p.put("sys", r.sys() == null ? null : (JsonNode) json.readTree(r.sys()));
            p.put("queue", json.readTree(r.queue()));
            return (Map<String, Object>) p;
        }).toList();
        var out = new LinkedHashMap<String, Object>();
        out.put("branch_id", b);
        out.put("from", start);
        out.put("to", end);
        out.put("points", points);
        return out;
    }
}
