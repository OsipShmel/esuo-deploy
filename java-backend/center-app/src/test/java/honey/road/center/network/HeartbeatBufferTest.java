package honey.road.center.network;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Heartbeats of known branches wait in memory until the next batch write (design 6.2). */
class HeartbeatBufferTest {
    private static final Instant T = Instant.parse("2026-10-02T10:00:00Z");
    private final HeartbeatBuffer buffer = new HeartbeatBuffer();

    private static Heartbeat hb(String branch, Instant at) {
        return new Heartbeat(branch, "name", "Europe/Moscow", "{}", at);
    }

    @Test
    void lastHeartbeatWins() {
        buffer.put(hb("b01", T));
        buffer.put(hb("b01", T.plusSeconds(10)));
        buffer.put(hb("b02", T));

        var drained = buffer.drain();

        assertThat(drained).hasSize(2);
        assertThat(drained.get("b01").receivedAt()).isEqualTo(T.plusSeconds(10));
    }

    @Test
    void drainEmptiesAtomically() {
        buffer.put(hb("b01", T));
        buffer.drain();

        assertThat(buffer.drain()).isEmpty();
    }

    @Test
    void restoreDoesNotOverwriteNewer() {
        buffer.put(hb("b01", T));
        buffer.put(hb("b02", T));
        var failed = buffer.drain();                                 // the write failed
        buffer.put(hb("b01", T.plusSeconds(10)));                    // meanwhile a fresher one came

        buffer.restore(List.copyOf(failed.values()));

        var again = buffer.drain();
        assertThat(again.get("b01").receivedAt()).isEqualTo(T.plusSeconds(10));
        assertThat(again.get("b02").receivedAt()).isEqualTo(T);
    }
}
