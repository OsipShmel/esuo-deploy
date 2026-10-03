package honey.road.center.network;

import honey.road.center.it.AbstractCenterIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/** The «Узел» line of a branch card (B7): a few numbers from the last heartbeat's {@code sys} and a link to the dashboard. */
@TestPropertySource(properties = "esuo.center.logs.grafana-public-url=http://grafana.test/")
class NodeLineIT extends AbstractCenterIT {
    @Autowired JdbcClient jdbc;

    private void branch(String id, String heartbeat) {
        jdbc.sql("""
                INSERT INTO branches(id,name,timezone,first_seen_at,last_heartbeat_at,last_heartbeat)
                VALUES (:id,'N','Europe/Moscow',now(),now(),CAST(:hb AS jsonb))
                ON CONFLICT (id) DO UPDATE SET last_heartbeat_at=now(), last_heartbeat=EXCLUDED.last_heartbeat""")
                .param("id", id).param("hb", heartbeat).update();
    }

    private JsonNode card(String id) {
        var result = mvc.get().uri("/api/v1/network/overview").header("Authorization", "Bearer " + adminToken()).exchange();
        result.assertThat().hasStatusOk();
        for (var b : json.readTree(result.getResponse().getContentAsByteArray()).path("branches")) {
            if (id.equals(b.path("id").asString())) {
                return b;
            }
        }
        throw new AssertionError(id + " is not in the overview");
    }

    @Test
    void nodeNumbersComeFromTheHeartbeat() {
        branch("nl1", """
                {"sys":{"cpu_system":0.423,"http_p95_ms":87,"disk_free_mb":20480,"disk_total_mb":102400,
                        "app_version":"1.2.3","uptime_sec":3600,"heap_used_mb":200}}""");

        var node = card("nl1").path("node");

        assertThat(node.path("cpu_pct").asInt()).isEqualTo(42);
        assertThat(node.path("http_p95_ms").asInt()).isEqualTo(87);
        assertThat(node.path("disk_free_mb").asLong()).isEqualTo(20480);
        assertThat(node.path("disk_total_mb").asLong()).isEqualTo(102400);
        assertThat(node.path("app_version").asString()).isEqualTo("1.2.3");
        assertThat(node.path("uptime_sec").asLong()).isEqualTo(3600);
        assertThat(node.has("heap_used_mb")).isFalse();                       // the card is not a dashboard
    }

    @Test
    void missingNumbersAreNull() {
        branch("nl2", "{\"sys\":{\"app_version\":\"1.2.3\"}}");

        var node = card("nl2").path("node");

        assertThat(node.path("cpu_pct").isNull()).isTrue();
        assertThat(node.path("http_p95_ms").isNull()).isTrue();
        assertThat(node.path("disk_free_mb").isNull()).isTrue();
    }

    @Test
    void branchWithoutSysHasNoNodeLine() {
        branch("nl3", "{\"waiting\":1}");

        assertThat(card("nl3").path("node").isNull()).isTrue();
    }

    @Test
    void dashboardLinkIsForThisBranch() {
        branch("nl4", "{}");

        assertThat(card("nl4").path("dashboard_url").asString()).isEqualTo("http://grafana.test/d/esuo-branch/?var-branch=nl4");
    }
}
