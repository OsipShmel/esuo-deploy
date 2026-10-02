package honey.road.center.logs;

import honey.road.contracts.http.EsuoHeaders;
import io.nats.client.impl.Headers;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** A message of CENTER_LOG → labels and time for Loki (design 5.6). */
class LogRecordsTest {
    private static final Instant FALLBACK = Instant.parse("2026-10-02T10:00:00Z");
    private static final String LINE = "{\"@timestamp\":\"2026-10-01T07:00:00.123456Z\",\"log\":{\"level\":\"INFO\",\"logger\":\"a.B\"},\"message\":\"x\"}";

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test
    void streamSubject() {
        var r = LogRecords.of("branch.b01.log.error", null, bytes(LINE), FALLBACK).orElseThrow();
        assertThat(r.branch()).isEqualTo("b01");
        assertThat(r.level()).isEqualTo("error");
        assertThat(r.source()).isEqualTo("stream");
        assertThat(r.pullId()).isNull();
        assertThat(r.line()).isEqualTo(LINE);
    }

    @Test
    void pullSubject() {
        var r = LogRecords.of("center.log.pull.b01", new Headers().add(EsuoHeaders.PULL_ID, "p-1"), bytes(LINE), FALLBACK)
                .orElseThrow();
        assertThat(r.branch()).isEqualTo("b01");
        assertThat(r.source()).isEqualTo("pull");
        assertThat(r.pullId()).isEqualTo("p-1");
        assertThat(r.level()).isEqualTo("info");
    }

    @Test
    void timestampFromLine() {
        var r = LogRecords.of("branch.b01.log.error", null, bytes(LINE), FALLBACK).orElseThrow();
        assertThat(r.tsNanos()).isEqualTo(1790838000123456000L);
    }

    @Test
    void badJsonFallsBack() {
        var stream = LogRecords.of("branch.b01.log.error", null, bytes("not json"), FALLBACK).orElseThrow();
        assertThat(stream.tsNanos()).isEqualTo(FALLBACK.getEpochSecond() * 1_000_000_000L);
        assertThat(stream.level()).isEqualTo("error");
        var pull = LogRecords.of("center.log.pull.b01", null, bytes("not json"), FALLBACK).orElseThrow();
        assertThat(pull.level()).isEqualTo("unknown");
        assertThat(pull.pullId()).isEqualTo("unknown");
    }

    @Test
    void foreignSubject() {
        assertThat(LogRecords.of("branch.b01.evt.ticket.issued", null, bytes(LINE), FALLBACK)).isEmpty();
        assertThat(LogRecords.of("center.log.pull", null, bytes(LINE), FALLBACK)).isEmpty();
    }
}
