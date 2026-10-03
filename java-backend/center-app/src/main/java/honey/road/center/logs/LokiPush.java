package honey.road.center.logs;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Body of {@code POST /loki/api/v1/push}: one stream per label set, values {@code [nanoseconds, line]} (design 5.6). */
public final class LokiPush {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private LokiPush() {
    }

    public static String payload(List<LogRecord> records) {
        var streams = new LinkedHashMap<Map<String, String>, ArrayNode>();
        for (var r : records) {
            streams.computeIfAbsent(labels(r), k -> JSON.createArrayNode())
                    .add(JSON.createArrayNode().add(Long.toString(r.tsNanos())).add(r.line()));
        }
        var body = JSON.createObjectNode();
        var array = body.putArray("streams");
        streams.forEach((labels, values) -> {
            var stream = array.addObject();
            var labelNode = stream.putObject("stream");
            labels.forEach(labelNode::put);
            stream.set("values", values);
        });
        return JSON.writeValueAsString(body);
    }

    /**
     * Few labels on purpose: branch, level, source. A pulled batch also gets its own {@code pull} label — otherwise
     * a second pull of an older interval would be rejected by Loki as out of order within the same stream.
     */
    private static Map<String, String> labels(LogRecord r) {
        var labels = new LinkedHashMap<String, String>();
        labels.put("service", "node");
        labels.put("branch", r.branch());
        labels.put("level", r.level());
        labels.put("source", r.source());
        if (r.pullId() != null) {
            labels.put("pull", r.pullId());
        }
        return labels;
    }
}
