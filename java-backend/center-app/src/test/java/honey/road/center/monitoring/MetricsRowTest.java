package honey.road.center.monitoring;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** One row of {@code branch_metrics} from a heartbeat (design 6.3). */
class MetricsRowTest {
    private static final Instant RECEIVED = Instant.parse("2026-10-02T10:00:10Z");
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void queueFieldsAreExtracted() {
        var hb = json.readTree("""
                {"waiting":4,"overdue":1,"oldest_wait_sec":300,"windows_open":2,"outbox_pending":7,"outbox_lag_sec":3,
                 "load_level":"high","clear_time_min":25,"services":[{"id":"x"}],"name":"n"}""");

        var row = MetricsRow.of("b01", hb, RECEIVED);

        assertThat(row.branchId()).isEqualTo("b01");
        assertThat(row.ts()).isEqualTo(RECEIVED);
        assertThat(row.queue().path("waiting").asInt()).isEqualTo(4);
        assertThat(row.queue().path("overdue").asInt()).isEqualTo(1);
        assertThat(row.queue().path("oldest_wait_sec").asInt()).isEqualTo(300);
        assertThat(row.queue().path("windows_open").asInt()).isEqualTo(2);
        assertThat(row.queue().path("outbox_pending").asInt()).isEqualTo(7);
        assertThat(row.queue().path("outbox_lag_sec").asInt()).isEqualTo(3);
        assertThat(row.queue().path("load_level").asString()).isEqualTo("high");
        assertThat(row.queue().path("clear_time_min").asInt()).isEqualTo(25);
        assertThat(row.queue().has("services")).isFalse();
    }

    @Test
    void clockSkewUsesSystemTime() {
        var hb = json.readTree("""
                {"waiting":0,"sent_at":"2026-10-02T13:00:00Z",
                 "sys":{"sent_at_system":"2026-10-02T10:00:08.500Z","cpu_count":4}}""");   // demo clock runs 3 h ahead

        var row = MetricsRow.of("b01", hb, RECEIVED);

        assertThat(row.clockSkewMs()).isEqualTo(1500);
        assertThat(row.sys().path("cpu_count").asInt()).isEqualTo(4);
    }

    @Test
    void noSysMeansNullSysAndSkew() {
        var row = MetricsRow.of("b01", json.readTree("{\"waiting\":0}"), RECEIVED);

        assertThat(row.sys()).isNull();
        assertThat(row.clockSkewMs()).isNull();
    }
}
