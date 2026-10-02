package honey.road.center.logs;

import honey.road.contracts.http.EsuoHeaders;
import io.nats.client.impl.Headers;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/** A CENTER_LOG message → {@link LogRecord}: labels from the subject, time from the line itself (design 5.6). */
public final class LogRecords {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String STREAM_PREFIX = "branch.";
    private static final String PULL_PREFIX = "center.log.pull.";

    private LogRecords() {
    }

    /**
     * @param fallback time to use when the line carries no readable {@code @timestamp}
     * @return empty for a subject that is not a log subject
     */
    public static Optional<LogRecord> of(String subject, @Nullable Headers headers, byte[] data, Instant fallback) {
        var line = new String(data, StandardCharsets.UTF_8);
        var json = parse(line);
        long ts = nanos(timestamp(json, fallback));
        if (subject.startsWith(PULL_PREFIX)) {
            var branch = subject.substring(PULL_PREFIX.length());
            if (branch.isEmpty() || branch.contains(".")) {
                return Optional.empty();
            }
            var level = json == null ? "unknown" : json.path("log").path("level").asString("unknown").toLowerCase(Locale.ROOT);
            var pullId = headers == null ? null : headers.getFirst(EsuoHeaders.PULL_ID);
            return Optional.of(new LogRecord(branch, level, "pull", pullId == null ? "unknown" : pullId, ts, line));
        }
        if (subject.startsWith(STREAM_PREFIX)) {
            var parts = subject.split("\\.");                     // branch.{b}.log.{level}
            if (parts.length == 4 && "log".equals(parts[2])) {
                return Optional.of(new LogRecord(parts[1], parts[3], "stream", null, ts, line));
            }
        }
        return Optional.empty();
    }

    private static @Nullable JsonNode parse(String line) {
        try {
            var node = JSON.readTree(line);
            return node != null && node.isObject() ? node : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Instant timestamp(@Nullable JsonNode json, Instant fallback) {
        if (json == null) {
            return fallback;
        }
        try {
            return Instant.parse(json.path("@timestamp").asString(""));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static long nanos(Instant t) {
        return t.getEpochSecond() * 1_000_000_000L + t.getNano();
    }
}
