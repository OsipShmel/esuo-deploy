package honey.road.center.logs;

import org.jspecify.annotations.Nullable;

/**
 * One log line on its way to Loki.
 *
 * @param source  {@code stream} (shipped by the branch collector) or {@code pull} (fetched on request)
 * @param pullId  the request a pulled line belongs to; {@code null} for streamed lines
 * @param tsNanos the line's own time, not the delivery time: lines from a link outage keep their moment
 * @param line    the raw ECS JSON line, byte for byte
 */
public record LogRecord(String branch, String level, String source, @Nullable String pullId, long tsNanos, String line) {
}
