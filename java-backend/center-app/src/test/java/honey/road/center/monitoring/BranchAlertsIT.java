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
    @Autowired BranchAlerts alerts;

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

    @org.junit.jupiter.api.AfterEach
    void cleanAlerts() {
        jdbc.sql("DELETE FROM branch_alerts WHERE branch_id LIKE 'bz-%' OR branch_id IN ('ba2','ba3')").update();
    }

    private void point(Instant ts, double cpu) {
        jdbc.sql("""
                INSERT INTO branch_metrics(branch_id,ts,cpu_system) VALUES ('ba1',:ts,:cpu)""")
                .param("ts", Timestamp.from(ts)).param("cpu", cpu).update();
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

    @Test
    void syncTakesMoreAlertsThanAStatementHasParameters() {        // a restarted swarm: every branch "restarted"
        var now = Instant.now();
        var many = new java.util.ArrayList<MonitoringRules.Alert>();
        for (int i = 0; i < 70_000; i++) {
            many.add(new MonitoringRules.Alert("bz-" + i, "sys.restart", "info", "restarted", 1, now));
        }

        alerts.sync(many, now);
        assertThat(jdbc.sql("SELECT count(*) FROM branch_alerts").query(Long.class).single()).isEqualTo(70_000);

        alerts.sync(many.subList(0, 10), now);
        assertThat(jdbc.sql("SELECT count(*) FROM branch_alerts").query(Long.class).single()).isEqualTo(10);
    }

    @Test
    void unchangedAlertIsNotRewritten() {
        var now = Instant.now();
        var alert = new MonitoringRules.Alert("ba2", "sys.disk", "critical", "8%", 0.08, now.minusSeconds(60));
        alerts.sync(java.util.List.of(alert), now);
        var version = jdbc.sql("SELECT xmin::text FROM branch_alerts WHERE branch_id='ba2'").query(String.class).single();

        alerts.sync(java.util.List.of(alert), now.plusSeconds(15));
        assertThat(jdbc.sql("SELECT xmin::text FROM branch_alerts WHERE branch_id='ba2'").query(String.class).single())
                .isEqualTo(version);

        alerts.sync(java.util.List.of(new MonitoringRules.Alert("ba2", "sys.disk", "critical", "7%", 0.07,
                now.minusSeconds(60))), now.plusSeconds(30));
        assertThat(jdbc.sql("SELECT text FROM branch_alerts WHERE branch_id='ba2'").query(String.class).single())
                .isEqualTo("7%");
    }

    @Test
    void sinceIsWrittenAsGiven() {                                  // the evaluator decides: kept or a new restart
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        alerts.sync(java.util.List.of(new MonitoringRules.Alert("ba3", "sys.restart", "info", "в 10:00", 1,
                now.minusSeconds(600))), now);

        alerts.sync(java.util.List.of(new MonitoringRules.Alert("ba3", "sys.restart", "info", "в 10:09", 1,
                now.minusSeconds(60))), now);

        assertThat(jdbc.sql("SELECT since FROM branch_alerts WHERE branch_id='ba3'").query(Timestamp.class).single()
                .toInstant()).isEqualTo(now.minusSeconds(60));
    }
}
