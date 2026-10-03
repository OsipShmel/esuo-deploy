package honey.road.node.monitoring;

import honey.road.contracts.error.ErrorCode;
import honey.road.contracts.error.EsuoException;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * RPC {@code diag.logs} (design 5.7): full node logs of any level for an interval, page by page. The center calls it
 * on request and forwards the lines to Loki; nothing leaves the branch unless asked for.
 */
@Component
public class DiagLogs {
    static final Duration DEFAULT_WINDOW = Duration.ofMinutes(30);
    static final int DEFAULT_LIMIT = 5000;
    static final int MAX_LIMIT = 20_000;
    static final int MAX_BYTES = 512 * 1024;                 // the RPC reply has to fit a 1 MB NATS message

    /** All fields optional: the last 30 minutes, every level from debug, first page. */
    public record Request(@Nullable Instant from, @Nullable Instant to, @Nullable String minLevel,
                          @Nullable String contains, @Nullable Integer limit, @Nullable String cursor) {
    }

    private final String file;
    private final Clock clock;

    public DiagLogs(@Value("${esuo.logs.file:}") String file, @Qualifier("realClock") Clock clock) {
        this.file = file == null ? "" : file.trim();
        this.clock = clock;
    }

    public LogFileReader.Page read(Request r) {
        if (file.isEmpty()) {
            throw new EsuoException(ErrorCode.LOGS_UNAVAILABLE, "Файл логов на отделении не настроен (LOG_FILE)");
        }
        var to = r.to() == null ? clock.instant() : r.to();
        var from = r.from() == null ? to.minus(DEFAULT_WINDOW) : r.from();
        if (!from.isBefore(to)) {
            throw new EsuoException(ErrorCode.VALIDATION_ERROR, "from должно быть раньше to");
        }
        var level = r.minLevel() == null ? "debug" : r.minLevel().toLowerCase(Locale.ROOT);
        if (!LogFileReader.isLevel(level)) {
            throw new EsuoException(ErrorCode.VALIDATION_ERROR, "min_level: trace, debug, info, warn или error");
        }
        int limit = Math.clamp(r.limit() == null ? DEFAULT_LIMIT : r.limit(), 1, MAX_LIMIT);
        return LogFileReader.read(Path.of(file), new LogFileReader.Query(from, to, level, r.contains(), limit, MAX_BYTES),
                r.cursor());
    }
}
