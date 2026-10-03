package honey.road.center.network;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** A buffered heartbeat keeps its body as UTF-8: the whole network waits in memory between flushes. */
class HeartbeatTest {

    @Test
    void bodyIsUtf8AndReadsBackTheSame() {
        var json = "{\"name\":\"Отделение № 1\",\"waiting\":3}";

        var hb = new Heartbeat("b01", "Отделение № 1", "Europe/Moscow", json, Instant.EPOCH);

        assertThat(hb.body()).isEqualTo(json.getBytes(StandardCharsets.UTF_8));
        assertThat(hb.body().length).isLessThan(json.length() * 2);    // a String with Cyrillic takes two bytes a char
        assertThat(hb.json()).isEqualTo(json);
    }
}
