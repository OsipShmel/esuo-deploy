package honey.road.node.monitoring;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads the node's own log files for {@code diag.logs} (design 5.7): rotated files {@code node.log.YYYY-MM-DD.i} oldest
 * first, then the current one; filters by time, minimum level and a case-insensitive substring; pages by line count and
 * bytes with a cursor {@code <file name>:<byte offset>}. Pure: no Spring, no clock.
 */
public final class LogFileReader {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> LEVELS = List.of("trace", "debug", "info", "warn", "error");

    /**
     * @param minLevel one of trace, debug, info, warn, error
     * @param contains case-insensitive substring of the raw line, or {@code null}
     * @param maxBytes page size in bytes of the lines; a single longer line is still returned, alone
     */
    public record Query(Instant from, Instant to, String minLevel, @Nullable String contains, int limit, int maxBytes) {
    }

    /** @param truncated more lines remain: read again with {@code nextCursor} */
    public record Page(List<String> lines, @Nullable String nextCursor, boolean truncated) {
    }

    private record LogFile(Path path, String date, int index) {
        String name() { return path.getFileName().toString(); }
    }

    private LogFileReader() {
    }

    public static boolean isLevel(String level) {
        return LEVELS.contains(level);
    }

    public static Page read(Path logFile, Query q, @Nullable String cursor) {
        var files = files(logFile);
        int start = 0;
        long offset = 0;
        if (cursor != null) {
            int colon = cursor.lastIndexOf(':');
            var name = colon < 0 ? cursor : cursor.substring(0, colon);
            var at = keyOf(logFile, name);
            start = files.size();
            for (int i = 0; i < files.size(); i++) {
                int cmp = ORDER.compare(files.get(i), at);
                if (cmp >= 0) {
                    start = i;
                    offset = cmp == 0 && colon >= 0 ? parseOffset(cursor.substring(colon + 1)) : 0;
                    break;
                }
            }
        }
        int minLevel = LEVELS.indexOf(q.minLevel());
        var needle = q.contains() == null || q.contains().isBlank() ? null : q.contains().toLowerCase(Locale.ROOT);
        var lines = new ArrayList<String>();
        long bytes = 0;
        for (int i = start; i < files.size(); i++) {
            var file = files.get(i);
            long from = i == start ? offset : 0;
            if (isOlderThan(file.path(), q.from())) {
                continue;
            }
            try (var in = new CountingReader(Files.newInputStream(file.path()), from)) {
                for (var line = in.next(); line != null; line = in.next()) {
                    if (!matches(line, q, minLevel, needle)) {
                        continue;
                    }
                    int size = line.getBytes(StandardCharsets.UTF_8).length + 1;
                    if (!lines.isEmpty() && (lines.size() >= q.limit() || bytes + size > q.maxBytes())) {
                        var next = file.name() + ":" + in.lineStart();
                        return new Page(List.copyOf(lines), next, true);
                    }
                    lines.add(line);
                    bytes += size;
                }
                if (lines.size() >= q.limit() || bytes >= q.maxBytes()) {
                    var next = nextAfter(files, i, in.position());
                    return next == null ? new Page(List.copyOf(lines), null, false) : new Page(List.copyOf(lines), next, true);
                }
            } catch (IOException e) {
                if (Files.exists(file.path())) {
                    throw new UncheckedIOException(e);
                }
                // rotated away while reading: the lines are in the next file
            }
        }
        return new Page(List.copyOf(lines), null, false);
    }

    /** Cursor right after the end of file {@code i}: the next file from its start, or end of data. */
    private static @Nullable String nextAfter(List<LogFile> files, int i, long position) {
        var file = files.get(i);
        try {
            if (position < Files.size(file.path())) {
                return file.name() + ":" + position;
            }
        } catch (IOException e) {
            // gone: continue with the next one
        }
        return i + 1 < files.size() ? files.get(i + 1).name() + ":0" : null;
    }

    private static boolean matches(String line, Query q, int minLevel, @Nullable String needle) {
        if (needle != null && !line.toLowerCase(Locale.ROOT).contains(needle)) {
            return false;
        }
        JsonNode json;
        try {
            json = JSON.readTree(line);
        } catch (RuntimeException e) {
            json = null;
        }
        if (json == null || !json.isObject()) {
            return true;                              // a broken line has neither time nor level: kept, as found
        }
        Instant ts;
        try {
            ts = Instant.parse(json.path("@timestamp").asString(""));
        } catch (RuntimeException e) {
            return true;
        }
        if (ts.isBefore(q.from()) || !ts.isBefore(q.to())) {
            return false;
        }
        int level = LEVELS.indexOf(json.path("log").path("level").asString("info").toLowerCase(Locale.ROOT));
        return level < 0 || level >= minLevel;
    }

    private static boolean isOlderThan(Path file, Instant from) {
        try {
            return Files.getLastModifiedTime(file).toInstant().isBefore(from);
        } catch (IOException e) {
            return false;
        }
    }

    private static final Comparator<LogFile> ORDER =
            Comparator.comparing(LogFile::date).thenComparingInt(LogFile::index);

    /** Rotated files by date and index, then the current file, which sorts last. */
    private static List<LogFile> files(Path logFile) {
        var dir = logFile.toAbsolutePath().getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        var out = new ArrayList<LogFile>();
        try (var listing = Files.list(dir)) {
            listing.forEach(p -> {
                var key = keyOf(logFile, p.getFileName().toString());
                if (key != null) {
                    out.add(new LogFile(p, key.date(), key.index()));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(ORDER);
        return out;
    }

    /** {@code node.log} → the newest key; {@code node.log.2026-10-01.3} → (2026-10-01, 3); anything else → null. */
    private static @Nullable LogFile keyOf(Path logFile, String name) {
        var base = logFile.getFileName().toString();
        if (name.equals(base)) {
            return new LogFile(logFile, "9999-99-99", Integer.MAX_VALUE);
        }
        var m = Pattern.compile(Pattern.quote(base) + "\\.(\\d{4}-\\d{2}-\\d{2})\\.(\\d+)").matcher(name);
        return m.matches() ? new LogFile(logFile.resolveSibling(name), m.group(1), Integer.parseInt(m.group(2))) : null;
    }

    private static long parseOffset(String s) {
        try {
            return Math.max(0, Long.parseLong(s));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Line reader that knows byte positions; a start inside a line skips to the next full line. */
    private static final class CountingReader implements AutoCloseable {
        private final InputStream in;
        private long position;
        private long lineStart;

        CountingReader(InputStream raw, long offset) throws IOException {
            this.in = new BufferedInputStream(raw, 64 * 1024);
            if (offset > 0) {
                long skipped = in.skip(offset - 1);
                position = skipped;
                if (skipped == offset - 1) {
                    int previous = in.read();
                    position++;
                    if (previous != '\n' && previous != -1) {
                        skipLine();
                    }
                }
            }
        }

        private void skipLine() throws IOException {
            for (int b = in.read(); b != -1; b = in.read()) {
                position++;
                if (b == '\n') {
                    return;
                }
            }
        }

        @Nullable String next() throws IOException {
            lineStart = position;
            var buf = new ByteArrayOutputStream(256);
            int b;
            while ((b = in.read()) != -1) {
                position++;
                if (b == '\n') {
                    return buf.toString(StandardCharsets.UTF_8);
                }
                buf.write(b);
            }
            position = lineStart;                              // a line without its newline is still being written
            return null;
        }

        long lineStart() { return lineStart; }

        long position() { return position; }

        @Override
        public void close() throws IOException { in.close(); }
    }
}
