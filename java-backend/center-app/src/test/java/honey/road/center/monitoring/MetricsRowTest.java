package honey.road.center.monitoring;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** One typed row of {@code branch_metrics} from a heartbeat (design 6.3, scale design 5.1). */
class MetricsRowTest {
    private static final Instant RECEIVED = Instant.parse("2026-10-02T10:00:10Z");
    private final JsonMapper json = JsonMapper.builder().build();

    private MetricsRow row(String heartbeat) {
        return MetricsRow.of("b01", json.readTree(heartbeat), RECEIVED);
    }

    @Test
    void queueFieldsAreExtracted() {
        var row = row("""
                {"waiting":4,"overdue":1,"oldest_wait_sec":300,"windows_open":2,"outbox_pending":7,"outbox_lag_sec":3,
                 "load_level":"high","clear_time_min":25.5,"services":[{"id":"x"}],"name":"n"}""");

        assertThat(row.branchId()).isEqualTo("b01");
        assertThat(row.ts()).isEqualTo(RECEIVED);
        assertThat(row.value("waiting")).isEqualTo(4);
        assertThat(row.value("overdue")).isEqualTo(1);
        assertThat(row.value("oldest_wait_sec")).isEqualTo(300);
        assertThat(row.value("windows_open")).isEqualTo(2);
        assertThat(row.value("outbox_pending")).isEqualTo(7);
        assertThat(row.value("outbox_lag_sec")).isEqualTo(3);
        assertThat(row.value("load_level")).isEqualTo("high");
        assertThat(row.value("clear_time_min")).isEqualTo(25.5f);
    }

    @Test
    void sysFieldsGetTheirColumnTypes() {
        var row = row("""
                {"waiting":0,"sys":{"cpu_system":0.95,"cpu_count":4,"http_requests":5000000000,"leaf_connected":true,
                 "disk_free_mb":180000,"http_p95_ms":42.5,"app_version":"1.2.3"}}""");

        assertThat(row.value("cpu_system")).isEqualTo(0.95f);
        assertThat(row.value("cpu_count")).isEqualTo(4);
        assertThat(row.value("http_requests")).isEqualTo(5_000_000_000L);
        assertThat(row.value("leaf_connected")).isEqualTo(true);
        assertThat(row.value("disk_free_mb")).isEqualTo(180_000);
        assertThat(row.value("http_p95_ms")).isEqualTo(42.5f);
        assertThat(row.value("heap_used_mb")).isNull();
    }

    @Test
    void garbageBecomesNullInsteadOfAFailedBatch() {
        var row = row("""
                {"waiting":"many","load_level":7,"sys":{"cpu_count":1e12,"heap_used_mb":"512","cpu_system":null,
                 "leaf_connected":"yes","db_pool_active":2.6}}""");

        assertThat(row.value("waiting")).isNull();
        assertThat(row.value("load_level")).isNull();
        assertThat(row.value("cpu_count")).isNull();                       // out of the int range
        assertThat(row.value("heap_used_mb")).isNull();
        assertThat(row.value("cpu_system")).isNull();
        assertThat(row.value("leaf_connected")).isNull();
        assertThat(row.value("db_pool_active")).isEqualTo(3);
    }

    @Test
    void clockSkewUsesSystemTime() {
        var row = row("""
                {"waiting":0,"sent_at":"2026-10-02T13:00:00Z",
                 "sys":{"sent_at_system":"2026-10-02T10:00:08.500Z","cpu_count":4}}""");   // demo clock runs 3 h ahead

        assertThat(row.value("clock_skew_ms")).isEqualTo(1500);
    }

    @Test
    void startedAtIsTakenFromTheNode() {
        var row = row("""
                {"sys":{"started_at":"2026-10-02T09:00:00.250Z","sent_at_system":"2026-10-02T10:00:08Z","uptime_sec":42}}""");

        assertThat(row.value("started_at")).isEqualTo(Instant.parse("2026-10-02T09:00:00.250Z"));
    }

    @Test
    void startedAtFallsBackToSystemTimeMinusUptime() {
        var row = row("""
                {"sys":{"sent_at_system":"2026-10-02T10:00:08Z","uptime_sec":3600}}""");

        assertThat(row.value("started_at")).isEqualTo(Instant.parse("2026-10-02T09:00:08Z"));
    }

    @Test
    void noSysMeansNoSysColumns() {
        var row = row("{\"waiting\":0}");

        assertThat(row.hasSys()).isFalse();
        assertThat(row.value("clock_skew_ms")).isNull();
        assertThat(row.value("started_at")).isNull();
        assertThat(row.value("cpu_count")).isNull();
    }
}
