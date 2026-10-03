package honey.road.node.monitoring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Full node logs on request (design 5.7): current and rotated files, filters, cursor, page limits. */
class LogFileReaderTest {
    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    @TempDir Path dir;

    private static String line(int minute, String level, String message) {
        return "{\"@timestamp\":\"" + T0.plusSeconds(60L * minute) + "\",\"log\":{\"level\":\"" + level
                + "\",\"logger\":\"a.B\"},\"message\":\"" + message + "\"}";
    }

    private Path write(String name, String... lines) throws IOException {
        var file = dir.resolve(name);
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    /** node.log.2026-10-01.0 (minutes 0-1), node.log.2026-10-01.1 (2-3), node.log (4-5). */
    private Path standard() throws IOException {
        write("node.log.2026-10-01.0", line(0, "INFO", "m0"), line(1, "ERROR", "m1"));
        write("node.log.2026-10-01.1", line(2, "WARN", "m2"), line(3, "DEBUG", "m3"));
        return write("node.log", line(4, "INFO", "m4"), line(5, "ERROR", "M5 Boom"));
    }

    private static LogFileReader.Query all() {
        return new LogFileReader.Query(T0.minusSeconds(3600), T0.plusSeconds(3600), "trace", null, 100, 512 * 1024);
    }

    private static List<String> messages(LogFileReader.Page page) {
        return page.lines().stream().map(l -> l.replaceAll(".*\"message\":\"([^\"]*)\".*", "$1")).toList();
    }

    @Test
    void readsRotatedThenCurrent() throws IOException {
        var page = LogFileReader.read(standard(), all(), null);

        assertThat(messages(page)).containsExactly("m0", "m1", "m2", "m3", "m4", "M5 Boom");
        assertThat(page.nextCursor()).isNull();
        assertThat(page.truncated()).isFalse();
    }

    @Test
    void filtersByTimeRange() throws IOException {
        var q = new LogFileReader.Query(T0.plusSeconds(60), T0.plusSeconds(4 * 60), "trace", null, 100, 512 * 1024);

        assertThat(messages(LogFileReader.read(standard(), q, null))).containsExactly("m1", "m2", "m3");
    }

    @Test
    void filtersByMinLevel() throws IOException {
        var q = new LogFileReader.Query(T0.minusSeconds(60), T0.plusSeconds(3600), "warn", null, 100, 512 * 1024);

        assertThat(messages(LogFileReader.read(standard(), q, null))).containsExactly("m1", "m2", "M5 Boom");
    }

    @Test
    void containsIsCaseInsensitive() throws IOException {
        var q = new LogFileReader.Query(T0.minusSeconds(60), T0.plusSeconds(3600), "trace", "boom", 100, 512 * 1024);

        assertThat(messages(LogFileReader.read(standard(), q, null))).containsExactly("M5 Boom");
    }

    @Test
    void stopsAtLimitWithCursorAndContinuesWithoutLossOrRepeat() throws IOException {
        var file = standard();
        var q = new LogFileReader.Query(T0.minusSeconds(60), T0.plusSeconds(3600), "trace", null, 2, 512 * 1024);

        var seen = new ArrayList<String>();
        String cursor = null;
        int pages = 0;
        do {
            var page = LogFileReader.read(file, q, cursor);
            assertThat(page.lines()).hasSizeLessThanOrEqualTo(2);
            seen.addAll(messages(page));
            cursor = page.nextCursor();
            assertThat(page.truncated()).isEqualTo(cursor != null);
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(seen).containsExactly("m0", "m1", "m2", "m3", "m4", "M5 Boom");
    }

    @Test
    void stopsAtMaxBytes() throws IOException {
        int oneLine = line(0, "INFO", "m0").length() + 1;
        var q = new LogFileReader.Query(T0.minusSeconds(60), T0.plusSeconds(3600), "trace", null, 100, oneLine * 2 + 10);

        var page = LogFileReader.read(standard(), q, null);

        assertThat(page.lines()).hasSize(2);
        assertThat(page.nextCursor()).isNotNull();
    }

    @Test
    void oversizedLineIsReturnedAlone() throws IOException {
        var huge = line(0, "ERROR", "x".repeat(5000));
        var file = write("node.log", huge, line(1, "INFO", "after"));
        var q = new LogFileReader.Query(T0.minusSeconds(60), T0.plusSeconds(3600), "trace", null, 100, 1000);

        var first = LogFileReader.read(file, q, null);
        assertThat(first.lines()).containsExactly(huge);
        var second = LogFileReader.read(file, q, first.nextCursor());
        assertThat(messages(second)).containsExactly("after");
    }

    @Test
    void brokenLineMatchedByContainsIsReturned() throws IOException {
        var file = write("node.log", line(0, "INFO", "ok"), "Exception in thread main OutOfMemoryError", line(1, "INFO", "ok2"));
        var q = new LogFileReader.Query(T0.minusSeconds(60), T0.plusSeconds(3600), "trace", "outofmemory", 100, 512 * 1024);

        assertThat(LogFileReader.read(file, q, null).lines()).containsExactly("Exception in thread main OutOfMemoryError");
    }

    @Test
    void cursorToMissingFileContinuesWithNext() throws IOException {
        var file = standard();
        var gone = "node.log.2026-09-30.4:120";               // rotated away and deleted by retention meanwhile

        assertThat(messages(LogFileReader.read(file, all(), gone))).containsExactly("m0", "m1", "m2", "m3", "m4", "M5 Boom");
    }

    @Test
    void cursorInsideALineSkipsToTheNextLine() throws IOException {
        var file = write("node.log", line(0, "INFO", "first"), line(1, "INFO", "second"));

        var page = LogFileReader.read(file, all(), "node.log:5");

        assertThat(messages(page)).containsExactly("second");
    }

    @Test
    void skipsFilesModifiedBeforeFrom() throws IOException {
        var file = standard();
        var old = dir.resolve("node.log.2026-10-01.0");
        Files.writeString(old, line(50, "ERROR", "lies about its time") + "\n");   // would match if it were read
        Files.setLastModifiedTime(old, FileTime.from(T0.minusSeconds(7200)));
        var q = new LogFileReader.Query(T0.plusSeconds(30 * 60), T0.plusSeconds(3600), "trace", null, 100, 512 * 1024);

        assertThat(LogFileReader.read(file, q, null).lines()).isEmpty();
    }

    @Test
    void lineStillBeingWrittenIsNotReturned() throws IOException {
        var file = dir.resolve("node.log");
        Files.writeString(file, line(0, "INFO", "done") + "\n" + "{\"@timestamp\":\"2026-10-01T10:01:00Z\",\"log\":{\"lev");

        var page = LogFileReader.read(file, all(), null);

        assertThat(messages(page)).containsExactly("done");
    }

    @Test
    void missingFileIsAnEmptyPage() {
        var page = LogFileReader.read(dir.resolve("node.log"), all(), null);

        assertThat(page.lines()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }
}
