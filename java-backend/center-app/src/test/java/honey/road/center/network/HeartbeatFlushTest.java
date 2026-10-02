package honey.road.center.network;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

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
}
