package honey.road.center.monitoring;

import honey.road.center.it.AbstractCenterIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Metrics history of a branch (design 6.3): API for the network admin over the typed columns (scale design 5.1). */
class BranchMetricsApiIT extends AbstractCenterIT {
    @Autowired JdbcClient jdbc;
    @Autowired BranchMetrics metrics;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @BeforeEach
    void branch() {
        jdbc.sql("DELETE FROM branch_metrics WHERE branch_id='bm1'").update();
        jdbc.sql("""
                INSERT INTO branches(id,name,timezone,first_seen_at,last_heartbeat_at,last_heartbeat)
                VALUES ('bm1','M','Europe/Moscow',now(),now(),'{}'::jsonb) ON CONFLICT (id) DO NOTHING""").update();
    }

    private void row(Duration ago, int waiting) {
        jdbc.sql("""
                INSERT INTO branch_metrics(branch_id,ts,cpu_count,waiting,clock_skew_ms) VALUES ('bm1',:ts,4,:w,120)""")
                .param("ts", Timestamp.from(now.minus(ago))).param("w", waiting).update();
    }

    private MvcTestResult get(String query, boolean withToken) {
        var request = mvc.get().uri("/api/v1/branches/bm1/metrics" + query);
        if (withToken) {
            request = request.header("Authorization", "Bearer " + adminToken());
        }
        return request.exchange();
    }

    private JsonNode body(MvcTestResult r) {
        return json.readTree(r.getResponse().getContentAsByteArray());
    }

    @Test
    void returnsTheLastHourByDefault() {
        row(Duration.ofHours(2), 1);
        row(Duration.ofMinutes(10), 7);

        var result = get("", true);

        result.assertThat().hasStatusOk();
        var rows = body(result);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("queue").path("waiting").asInt()).isEqualTo(7);
        assertThat(rows.get(0).path("sys").path("cpu_count").asInt()).isEqualTo(4);
        assertThat(rows.get(0).path("clock_skew_ms").asInt()).isEqualTo(120);
        assertThat(rows.get(0).path("ts").asString()).isNotBlank();
    }

    @Test
    void explicitRangeInAscendingOrder() {
        row(Duration.ofHours(3), 1);
        row(Duration.ofHours(2), 2);

        var rows = body(get("?from=" + now.minus(Duration.ofHours(4)) + "&to=" + now, true));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).path("queue").path("waiting").asInt()).isEqualTo(1);
    }

    @Test
    void rangeOver48hIs400() {
        var result = get("?from=" + now.minus(Duration.ofHours(49)) + "&to=" + now, true);

        result.assertThat().hasStatus(400);
        assertThat(body(result).path("code").asString()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void fromAfterToIs400() {
        get("?from=" + now + "&to=" + now.minusSeconds(60), true).assertThat().hasStatus(400);
    }

    @Test
    void unknownBranchIs404() {
        var result = mvc.get().uri("/api/v1/branches/nope-404/metrics").header("Authorization", "Bearer " + adminToken()).exchange();

        result.assertThat().hasStatus(404);
        assertThat(body(result).path("code").asString()).isEqualTo("BRANCH_NOT_FOUND");
    }

    @Test
    void requiresAdmin() {
        get("", false).assertThat().hasStatus(401);
    }

    @Test
    void everyColumnRoundTrips() {
        var sys = json.createObjectNode();
        int n = 1;
        for (var c : MetricColumns.SYS) {
            if (c.type() == MetricColumns.Type.BOOL) {
                sys.put(c.name(), true);
            } else {
                sys.put(c.name(), n++);
            }
        }
        sys.put("started_at", "2026-10-02T09:00:00Z").put("sent_at_system", now.minusSeconds(2).toString());
        var hb = json.createObjectNode().put("waiting", 5).put("overdue", 1).put("oldest_wait_sec", 300)
                .put("windows_open", 2).put("outbox_pending", 7).put("outbox_lag_sec", 3).put("load_level", "high")
                .put("clear_time_min", 12.5);
        hb.set("sys", sys);

        metrics.insert(java.util.List.of(MetricsRow.of("bm1", hb, now.minusSeconds(30))));

        var row = body(get("", true)).get(0);
        n = 1;
        for (var c : MetricColumns.SYS) {
            var v = row.path("sys").path(c.name());
            if (c.type() == MetricColumns.Type.BOOL) {
                assertThat(v.asBoolean()).as(c.name()).isTrue();
            } else {
                assertThat(v.asDouble()).as(c.name()).isEqualTo(n++);
            }
        }
        assertThat(row.path("sys").path("started_at").asString()).isEqualTo("2026-10-02T09:00:00Z");
        assertThat(row.path("clock_skew_ms").asInt()).isEqualTo(-28_000);
        assertThat(row.path("queue").path("waiting").asInt()).isEqualTo(5);
        assertThat(row.path("queue").path("load_level").asString()).isEqualTo("high");
        assertThat(row.path("queue").path("clear_time_min").asDouble()).isEqualTo(12.5);
        assertThat(row.path("queue").size()).isEqualTo(MetricColumns.QUEUE.size());
    }

    @Test
    void rowWithoutSysHasNullSys() {
        metrics.insert(java.util.List.of(MetricsRow.of("bm1", json.readTree("{\"waiting\":3}"), now.minusSeconds(30))));

        var row = body(get("", true)).get(0);
        assertThat(row.path("sys").isNull()).isTrue();
        assertThat(row.path("clock_skew_ms").isNull()).isTrue();
        assertThat(row.path("queue").path("waiting").asInt()).isEqualTo(3);
    }
}
