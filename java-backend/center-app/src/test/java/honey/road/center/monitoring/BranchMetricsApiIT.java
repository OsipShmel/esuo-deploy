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

/** Metrics history of a branch (design 6.3): API for the network admin and the 48 h retention. */
class BranchMetricsApiIT extends AbstractCenterIT {
    @Autowired JdbcClient jdbc;
    @Autowired MetricsRetention retention;

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
                INSERT INTO branch_metrics(branch_id,ts,sys,queue,clock_skew_ms)
                VALUES ('bm1',:ts,'{"cpu_count":4}'::jsonb,CAST(:q AS jsonb),120)""")
                .param("ts", Timestamp.from(now.minus(ago))).param("q", "{\"waiting\":" + waiting + "}").update();
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
    void retentionDeletesOldRows() {
        row(Duration.ofHours(49), 1);
        row(Duration.ofHours(47), 2);

        retention.run();

        assertThat(jdbc.sql("SELECT (queue->>'waiting')::int FROM branch_metrics WHERE branch_id='bm1'").query(Integer.class).list())
                .containsExactly(2);
    }
}
