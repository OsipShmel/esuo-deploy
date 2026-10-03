package honey.road.center.network;

import honey.road.center.it.AbstractCenterIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Network overview for thousands of branches (final plan, C3c): a summary with counts and the worst branches first,
 * and a paged, searchable list instead of every card on every poll. The full {@code /network/overview} stays for the
 * scripts; the summary must agree with it. Other test classes share the database, so own branches carry the
 * {@code ns-} prefix and totals are compared with the overview rather than with fixed numbers.
 */
class NetworkSummaryIT extends AbstractCenterIT {
    @Autowired JdbcClient jdbc;
    private String token;

    private void branch(String id, int ageSec, String heartbeat) {
        jdbc.sql("""
                INSERT INTO branches(id,name,timezone,first_seen_at,last_heartbeat_at,last_heartbeat,source_attached)
                VALUES (:id,:name,'Europe/Moscow',now(),now() - make_interval(secs => :age),CAST(:hb AS jsonb),true)
                ON CONFLICT (id) DO UPDATE SET last_heartbeat_at=EXCLUDED.last_heartbeat_at, last_heartbeat=EXCLUDED.last_heartbeat""")
                .param("id", id).param("name", "Отделение " + id).param("age", ageSec).param("hb", heartbeat).update();
    }

    @BeforeEach
    void network() {
        token = adminToken();
        branch("ns-ok1", 2, "{\"waiting\":2,\"windows_open\":2,\"windows_total\":3}");
        branch("ns-ok2", 2, "{\"waiting\":0,\"windows_open\":1,\"windows_total\":1}");
        branch("ns-ok3", 2, "{\"waiting\":1,\"windows_open\":1,\"windows_total\":2,\"load_level\":\"high\"}");
        branch("ns-off", 600, "{\"waiting\":4,\"windows_open\":2}");
        branch("ns-over", 2, "{\"waiting\":12,\"overdue\":9,\"windows_open\":2}");
        branch("ns-shut", 2, "{\"waiting\":4,\"windows_open\":0,\"overdue\":7}");
        branch("ns-r1a", 2, "{\"waiting\":1,\"windows_open\":1,\"events_via\":\"region-9\"}");
        branch("ns-r1b", 600, "{\"waiting\":1,\"windows_open\":1,\"events_via\":\"region-9\"}");
    }

    private JsonNode get(String uri) {
        var result = mvc.get().uri(uri).header("Authorization", "Bearer " + token).exchange();
        result.assertThat().hasStatusOk();
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private static List<String> ids(JsonNode cards) {
        var out = new ArrayList<String>();
        cards.forEach(c -> out.add(c.path("id").asString()));
        return out;
    }

    @Test
    void summaryAgreesWithTheFullOverview() {
        var overview = get("/api/v1/network/overview");
        var summary = get("/api/v1/network/summary");

        assertThat(summary.path("branches_total").asInt()).isEqualTo(overview.path("branches_total").asInt());
        assertThat(summary.path("branches_online").asInt()).isEqualTo(overview.path("branches_online").asInt());
        assertThat(summary.path("branches_offline").asInt())
                .isEqualTo(overview.path("branches_total").asInt() - overview.path("branches_online").asInt());

        Map<String, Integer> counted = new HashMap<>();
        int withProblems = 0;
        for (var b : overview.path("branches")) {
            if (!b.path("anomalies").isEmpty()) { withProblems++; }
            b.path("anomalies").forEach(a -> counted.merge(a.asString(), 1, Integer::sum));
        }
        Map<String, Integer> reported = new HashMap<>();
        summary.path("anomalies").properties().forEach(e -> reported.put(e.getKey(), e.getValue().asInt()));
        assertThat(reported).isEqualTo(counted);
        assertThat(summary.path("problem_branches_total").asInt()).isEqualTo(withProblems);
    }

    @Test
    void worstBranchesComeFirstAndHealthyOnesAreNotProblems() {
        var problems = ids(get("/api/v1/network/summary?top=100").path("problems")).stream()
                .filter(id -> id.startsWith("ns-")).toList();

        // no_open_windows + overdue (two problems, one red) > offline (red) > overdue only
        assertThat(problems).startsWith("ns-shut");
        assertThat(problems.indexOf("ns-off")).isLessThan(problems.indexOf("ns-over"));
        assertThat(problems).contains("ns-r1b").doesNotContain("ns-ok1", "ns-ok2", "ns-ok3", "ns-r1a");
    }

    @Test
    void problemListIsCappedButTheCountIsNot() {
        var summary = get("/api/v1/network/summary?top=1");
        assertThat(summary.path("problems")).hasSize(1);
        assertThat(summary.path("problem_branches_total").asInt()).isGreaterThanOrEqualTo(4);
        assertThat(get("/api/v1/network/summary?top=100000").path("problems").size()).isLessThanOrEqualTo(100);
    }

    @Test
    void cardsOfTheSummaryAreTheOverviewCards() {
        var card = get("/api/v1/network/summary?top=100").path("problems").valueStream()
                .filter(c -> c.path("id").asString().equals("ns-shut")).findFirst().orElseThrow();
        assertThat(card.path("anomalies").valueStream().map(JsonNode::asString).toList())
                .contains("no_open_windows", "overdue_tickets");
        assertThat(card.path("overdue").asInt()).isEqualTo(7);
        assertThat(card.has("node")).isTrue();                     // same card as /overview, buttons keep working
    }

    @Test
    void branchesArePagedSearchableAndFiltered() {
        var first = get("/api/v1/network/branches?q=ns-&limit=3");
        assertThat(first.path("total").asInt()).isEqualTo(8);
        assertThat(ids(first.path("items"))).containsExactly("ns-off", "ns-ok1", "ns-ok2");   // by id
        assertThat(ids(get("/api/v1/network/branches?q=ns-&limit=3&offset=6").path("items")))
                .containsExactly("ns-r1b", "ns-shut");

        assertThat(ids(get("/api/v1/network/branches?q=ns-&status=offline").path("items")))
                .containsExactlyInAnyOrder("ns-off", "ns-r1b");
        assertThat(ids(get("/api/v1/network/branches?q=ns-&status=problems").path("items")))
                .startsWith("ns-shut").containsExactlyInAnyOrder("ns-shut", "ns-off", "ns-r1b", "ns-over");
        assertThat(get("/api/v1/network/branches?q=ns-&status=online").path("total").asInt()).isEqualTo(6);
        assertThat(ids(get("/api/v1/network/branches?q=отделение ns-ok2").path("items"))).containsExactly("ns-ok2");
    }

    @Test
    void pageSizeIsCapped() {
        assertThat(get("/api/v1/network/branches?limit=100000").path("limit").asInt()).isEqualTo(200);
        assertThat(get("/api/v1/network/branches?limit=0").path("limit").asInt()).isEqualTo(1);
    }

    @Test
    void regionsAreCounted() {
        var region = get("/api/v1/network/summary").path("regions").valueStream()
                .filter(r -> "region-9".equals(r.path("events_via").asString(null))).findFirst().orElseThrow();
        assertThat(region.path("total").asInt()).isEqualTo(2);
        assertThat(region.path("online").asInt()).isEqualTo(1);
        assertThat(region.path("problems").asInt()).isEqualTo(1);
    }
}
