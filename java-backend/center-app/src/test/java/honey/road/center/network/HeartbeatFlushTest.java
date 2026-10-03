package honey.road.center.network;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Metric rows follow the resolution, with room for heartbeats arriving a second early (design 6.3). */
class HeartbeatFlushTest {
    private static final Instant T = Instant.parse("2026-10-02T10:00:00Z");

    @Test
    void dueToleratesJitter() {
        var resolution = Duration.ofSeconds(10);

        assertThat(HeartbeatFlush.due(null, T, resolution)).isTrue();
        assertThat(HeartbeatFlush.due(T, T.plusSeconds(9), resolution)).isTrue();
        assertThat(HeartbeatFlush.due(T, T.plusSeconds(7), resolution)).isFalse();
        assertThat(HeartbeatFlush.due(T, T.plusSeconds(50), Duration.ofSeconds(60))).isTrue();
        assertThat(HeartbeatFlush.due(T, T.plusSeconds(40), Duration.ofSeconds(60))).isFalse();
    }

    @Test
    void anOrdinaryBranchIsDueOnFirstSight() {
        assertThat(new HeartbeatFlush.Cadence().due("b01", T, Duration.ofSeconds(10), false)).isTrue();
    }

    /**
     * Branches behind a regional hub, seen for the first time after a center start, every heartbeat 10 s apart: each
     * writes once per resolution, at its own moment of it. Without that all of them fall due in the same flush once a
     * minute, and at 250 000 that flush outlasted the offline threshold (scale design 9.2).
     */
    @Test
    void regionalBranchesAreSpreadOverTheResolution() {
        var cadence = new HeartbeatFlush.Cadence();
        var resolution = Duration.ofSeconds(60);
        int branches = 6000;
        var perBeat = new int[18];                                       // three minutes
        var perBranch = new int[branches];
        for (int beat = 0; beat < perBeat.length; beat++) {
            var at = T.plusSeconds(10L * beat);
            for (int i = 0; i < branches; i++) {
                var id = "s1-%05d".formatted(i);
                if (cadence.due(id, at, resolution, true)) {
                    cadence.written(id, at);
                    perBeat[beat]++;
                    perBranch[i]++;
                }
            }
        }

        assertThat(IntStream.of(perBeat).max().orElseThrow()).isLessThanOrEqualTo(branches / 4);    // no wave
        assertThat(IntStream.of(perBranch).min().orElseThrow()).isGreaterThanOrEqualTo(3);         // once a minute each
    }
}
