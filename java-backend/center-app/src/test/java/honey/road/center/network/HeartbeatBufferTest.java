package honey.road.center.network;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** C3b: receiving is cheap and bounded, the flush gets one latest heartbeat per branch. */
class HeartbeatBufferTest {
    private static final Instant T = Instant.parse("2026-10-05T09:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test
    void latestHeartbeatOfEachBranchWins() {
        var buffer = new HeartbeatBuffer(100);
        buffer.offer("b01", bytes("{\"waiting\":1}"), T);
        buffer.offer("b01", bytes("{\"waiting\":2}"), T.plusSeconds(10));
        buffer.offer("b02", bytes("{\"waiting\":7}"), T);
        var drained = buffer.drain();
        assertThat(drained).hasSize(2);
        var b01 = drained.stream().filter(r -> r.branchId().equals("b01")).findFirst().orElseThrow();
        assertThat(new String(b01.body(), StandardCharsets.UTF_8)).contains("\"waiting\":2");
        assertThat(b01.receivedAt()).isEqualTo(T.plusSeconds(10));
        assertThat(buffer.drain()).isEmpty();                     // a drain takes everything
    }

    @Test
    void memoryIsBoundedByBranchesNotByBacklog() {
        var buffer = new HeartbeatBuffer(2);
        for (int i = 0; i < 10_000; i++) { buffer.offer("b01", bytes("{}"), T); }
        assertThat(buffer.size()).isEqualTo(1);
        assertThat(buffer.offer("b02", bytes("{}"), T)).isTrue();
        assertThat(buffer.offer("b03", bytes("{}"), T)).isFalse();   // over the cap: waits for the next flush
        assertThat(buffer.offer("b01", bytes("{}"), T)).isTrue();    // known branches still refresh
        assertThat(buffer.dropped()).isEqualTo(1);
    }

    @Test
    void rowSplitsMetricsAndKeepsFallbacks() {
        var hb = """
                {"branch_id":"s1-00001","name":"Виртуальное отделение s1-00001","timezone":"Asia/Yekaterinburg",
                 "waiting":5,"overdue":1,"windows_open":2,"load_level":"high","services":[{"id":"x"}],
                 "events_via":"region-1","sys":{"cpu_system":0.42,"http_p95_ms":120}}""";
        var row = HeartbeatRow.parse(JSON, new HeartbeatBuffer.Received("s1-00001", bytes(hb), T));
        assertThat(row.name()).isEqualTo("Виртуальное отделение s1-00001");
        assertThat(row.timezone()).isEqualTo("Asia/Yekaterinburg");
        assertThat(row.eventsVia()).isEqualTo("region-1");
        assertThat(JSON.readTree(row.sys()).path("cpu_system").asDouble()).isEqualTo(0.42);
        var queue = JSON.readTree(row.queue());
        assertThat(queue.path("waiting").asInt()).isEqualTo(5);
        assertThat(queue.path("load_level").asString()).isEqualTo("high");
        assertThat(queue.has("services")).isFalse();             // the catalog stays in last_heartbeat only
        assertThat(row.json()).isEqualTo(hb);

        // A node without the new fields: defaults as before, no sys, no region.
        var old = HeartbeatRow.parse(JSON, new HeartbeatBuffer.Received("b01", bytes("{\"waiting\":0}"), T));
        assertThat(old.name()).isEqualTo("b01");
        assertThat(old.timezone()).isEqualTo("Europe/Moscow");
        assertThat(old.eventsVia()).isNull();
        assertThat(old.sys()).isNull();
    }
}
