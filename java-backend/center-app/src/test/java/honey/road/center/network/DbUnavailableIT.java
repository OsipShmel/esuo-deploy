package honey.road.center.network;

import honey.road.center.it.AbstractCenterIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/** A branch whose database does not answer still sends heartbeats: online, with its own anomaly, not «no link». */
class DbUnavailableIT extends AbstractCenterIT {
    @Autowired JdbcClient jdbc;

    private void branch(String id, String heartbeat) {
        jdbc.sql("""
                INSERT INTO branches(id,name,timezone,first_seen_at,last_heartbeat_at,last_heartbeat)
                VALUES (:id,'D','Europe/Moscow',now(),now(),CAST(:hb AS jsonb))
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
    void stuckDatabaseIsItsOwnAnomaly() {
        branch("du1", "{\"waiting\":4,\"windows_open\":1,\"db_ok\":false,\"db_unavailable_sec\":42}");

        var card = card("du1");

        assertThat(card.path("online").asBoolean()).isTrue();
        assertThat(card.path("anomalies").toString()).contains("\"db_unavailable\"").doesNotContain("\"offline\"");
        assertThat(card.path("db_unavailable_sec").asInt()).isEqualTo(42);
    }

    @Test
    void answeringDatabaseIsNoAnomaly() {
        branch("du2", "{\"waiting\":0,\"windows_open\":1}");

        var card = card("du2");

        assertThat(card.path("anomalies").toString()).doesNotContain("db_unavailable");
        assertThat(card.path("db_unavailable_sec").isNull()).isTrue();
    }
}
