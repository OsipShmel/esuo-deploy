package honey.road.center.monitoring;

import honey.road.center.it.AbstractCenterIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rules over {@code branch_metrics} → {@code branch_alerts} → network overview (design 6.4). Evaluation is explicit
 * here: the schedule is an hour.
 */
@TestPropertySource(properties = "esuo.center.monitoring.evaluate-every=1h")
class BranchAlertsIT extends AbstractCenterIT {
    @Autowired JdbcClient jdbc;
    @Autowired RuleEvaluator evaluator;

    @BeforeEach
    void hotBranch() {
        jdbc.sql("DELETE FROM branch_metrics WHERE branch_id='ba1'").update();
        jdbc.sql("""
                INSERT INTO branches(id,name,timezone,first_seen_at,last_heartbeat_at,last_heartbeat)
                VALUES ('ba1','A','Europe/Moscow',now(),now(),'{}'::jsonb)
                ON CONFLICT (id) DO UPDATE SET last_heartbeat_at=now()""").update();
        var now = Instant.now();
        for (int s = 360; s >= 0; s -= 10) {
            point(now.minusSeconds(s), 0.95);
        }
    }

    private void point(Instant ts, double cpu) {
        jdbc.sql("""
                INSERT INTO branch_metrics(branch_id,ts,sys,queue) VALUES ('ba1',:ts,CAST(:sys AS jsonb),'{}'::jsonb)""")
                .param("ts", Timestamp.from(ts)).param("sys", "{\"cpu_system\":" + cpu + "}").update();
    }

    private JsonNode card() {
        var result = mvc.get().uri("/api/v1/network/overview").header("Authorization", "Bearer " + adminToken()).exchange();
        result.assertThat().hasStatusOk();
        for (var b : json.readTree(result.getResponse().getContentAsByteArray()).path("branches")) {
            if ("ba1".equals(b.path("id").asString())) {
                return b;
            }
        }
        throw new AssertionError("ba1 is not in the overview");
    }

    private Instant since() {
        return jdbc.sql("SELECT since FROM branch_alerts WHERE branch_id='ba1' AND rule_id='sys.cpu'")
                .query(Timestamp.class).single().toInstant();
    }

    @Test
    void ruleFiresAndShowsInOverview() {
        evaluator.run();

        var card = card();
        assertThat(card.path("anomalies").toString()).contains("\"sys.cpu\"");
        var alert = card.path("alerts").get(0);
        assertThat(alert.path("code").asString()).isEqualTo("sys.cpu");
        assertThat(alert.path("severity").asString()).isEqualTo("warning");
        assertThat(alert.path("text").asString()).startsWith("ПК отделения не справляется: CPU 95%");
        assertThat(alert.path("since").asString()).isNotBlank();
    }

    @Test
    void continuingAlertKeepsSince() throws Exception {
        evaluator.run();
        var first = since();
        Thread.sleep(20);
        jdbc.sql("DELETE FROM branch_metrics WHERE branch_id='ba1' AND ts < now() - interval '330 seconds'").update();
        point(Instant.now(), 0.97);

        evaluator.run();

        assertThat(since()).isEqualTo(first);
    }

    @Test
    void resolvedAlertIsRemoved() {
        evaluator.run();
        point(Instant.now(), 0.2);

        evaluator.run();

        assertThat(card().path("alerts")).isEmpty();
        assertThat(card().path("anomalies").toString()).doesNotContain("sys.cpu");
    }
}
