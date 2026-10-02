package honey.road.node.monitoring;

import honey.road.node.monitoring.DiagnosticsSnapshot.Section;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** How a snapshot is put together (design 7): sections on their own, a deadline, a size limit. */
class DiagnosticsSnapshotTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T10:00:00Z"), ZoneOffset.UTC);

    private static DiagnosticsSnapshot snapshot(Duration deadline, int maxBytes, Section... sections) {
        return new DiagnosticsSnapshot(List.of(sections), deadline, maxBytes, CLOCK);
    }

    @Test
    void failingSectionDoesNotSpoilOthers() {
        var s = snapshot(Duration.ofSeconds(5), 512 * 1024,
                new Section("app", () -> Map.of("version", "1")),
                new Section("db", () -> { throw new IllegalStateException("connection refused"); }),
                new Section("disk", () -> Map.of("free_mb", 10))).take();

        assertThat(s).containsEntry("app", Map.of("version", "1")).containsEntry("disk", Map.of("free_mb", 10));
        assertThat(s.get("db")).isEqualTo(Map.of("error", "IllegalStateException: connection refused"));
        assertThat(s).containsEntry("taken_at", CLOCK.instant()).containsEntry("truncated", false);
    }

    @Test
    void slowSectionIsCutByTimeout() {
        long started = System.nanoTime();
        var s = snapshot(Duration.ofMillis(300), 512 * 1024,
                new Section("db", () -> { sleep(5_000); return Map.of(); }),
                new Section("app", () -> Map.of("version", "1"))).take();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(s.get("db")).isEqualTo(Map.of("error", "timed out after 300 ms"));
        assertThat(s).containsEntry("app", Map.of("version", "1"));
    }

    @Test
    void sectionsKeepTheirOrder() {
        var s = snapshot(Duration.ofSeconds(5), 512 * 1024,
                new Section("app", Map::of), new Section("jvm", Map::of), new Section("db", Map::of)).take();

        assertThat(s.keySet()).containsExactly("taken_at", "truncated", "app", "jvm", "db");
    }

    @Test
    void oversizedSnapshotLosesStacksFirst() {
        var frames = List.of("x".repeat(3000), "y".repeat(3000));
        var s = snapshot(Duration.ofSeconds(5), 4_000,
                new Section("jvm", () -> Map.of("blocked", List.of(Map.of("name", "t1", "stack", frames)))),
                new Section("app", () -> Map.of("version", "1"))).take();

        assertThat(s).containsEntry("truncated", true);
        assertThat(s.get("jvm")).isEqualTo(Map.of("blocked", List.of(Map.of("name", "t1"))));
        assertThat(s).containsEntry("app", Map.of("version", "1"));
    }

    @Test
    void stillOversizedSectionIsReplacedByAnError() {
        var s = snapshot(Duration.ofSeconds(5), 2_000,
                new Section("db", () -> Map.of("activity", List.of("q".repeat(5000)))),
                new Section("app", () -> Map.of("version", "1"))).take();

        assertThat(s).containsEntry("truncated", true).containsEntry("app", Map.of("version", "1"));
        assertThat(((Map<?, ?>) s.get("db")).get("error").toString()).startsWith("too large");
        assertThat(JsonMapper.builder().build().writeValueAsString(s).length()).isLessThanOrEqualTo(2_000);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
