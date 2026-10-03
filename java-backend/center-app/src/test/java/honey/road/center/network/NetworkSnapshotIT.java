package honey.road.center.network;

import honey.road.center.it.AbstractCenterIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One picture of the network for every screen and every poll (scale design 8.1, 8.2): at 250 000 branches judging them
 * costs the database seconds, so it is done once in a while and shared — not once per tab per poll. The full overview,
 * every card at once, is refused above a limit: it took the center down.
 */
@TestPropertySource(properties = {"esuo.center.network.snapshot-max-age=1h", "esuo.center.network.overview-max=3"})
class NetworkSnapshotIT extends AbstractCenterIT {
    @Autowired JdbcClient jdbc;

    private void branch(String id) {
        jdbc.sql("""
                INSERT INTO branches(id,name,timezone,first_seen_at,last_heartbeat_at,last_heartbeat)
                VALUES (:id,'S','Europe/Moscow',now(),now(),'{}'::jsonb) ON CONFLICT (id) DO NOTHING""")
                .param("id", id).update();
    }

    private JsonNode summary(String token) {
        var result = mvc.get().uri("/api/v1/network/summary").header("Authorization", "Bearer " + token).exchange();
        result.assertThat().hasStatusOk();
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    @Test
    void oneSnapshotServesEveryoneUntilItIsOld() {
        var token = adminToken();
        branch("sn-1");
        var first = summary(token);

        branch("sn-2");
        var second = summary(token);

        assertThat(second.path("generated_at").asString()).isEqualTo(first.path("generated_at").asString());
        assertThat(second.path("branches_total").asInt()).isEqualTo(first.path("branches_total").asInt());
    }

    @Test
    void theFullOverviewIsRefusedAboveTheLimit() {
        for (int i = 1; i <= 4; i++) {
            branch("sn-o" + i);
        }

        var result = mvc.get().uri("/api/v1/network/overview").header("Authorization", "Bearer " + adminToken()).exchange();

        result.assertThat().hasStatus(422);
        assertThat(json.readTree(result.getResponse().getContentAsByteArray()).path("code").asString())
                .isEqualTo("NETWORK_TOO_LARGE");
    }
}
