package honey.road.center.diagnostics;

import honey.road.center.it.AbstractCenterNatsIT;
import honey.road.contracts.event.Subjects;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Diagnostics snapshots (design 7): taken over RPC, stored 30 days, listed without the payload. The branch is this test. */
class DiagnosticsSnapshotApiIT extends AbstractCenterNatsIT {
    static final String SNAPSHOT = "{\"ok\":true,\"status\":200,\"data\":{\"taken_at\":\"2026-10-03T10:00:00Z\",\"truncated\":false,"
            + "\"app\":{\"branch_id\":\"b01\"},\"jvm\":{\"deadlocks\":[]},\"db\":{\"error\":\"timed out after 8000 ms\"}},\"problem\":null}";

    @Autowired JdbcClient jdbc;
    @Autowired SnapshotRetention retention;

    Connection branch;
    Dispatcher dispatcher;

    @BeforeEach
    void branchOnline() throws Exception {
        jdbc.sql("DELETE FROM branch_diagnostics").update();
        branch = testClient();
        dispatcher = branch.createDispatcher(msg -> branch.publish(msg.getReplyTo(), SNAPSHOT.getBytes(StandardCharsets.UTF_8)));
        dispatcher.subscribe(Subjects.rpc("b01", "diag.snapshot"));
        branch.flush(Duration.ofSeconds(2));
    }

    @AfterEach
    void branchOffline() throws Exception {
        branch.close();
    }

    private MvcTestResult take(String branchId) {
        return mvc.post().uri("/api/v1/branches/" + branchId + "/diagnostics/snapshots")
                .header("Authorization", "Bearer " + adminToken()).exchange();
    }

    private MvcTestResult get(String uri) {
        return mvc.get().uri(uri).header("Authorization", "Bearer " + adminToken()).exchange();
    }

    private JsonNode body(MvcTestResult r) {
        return json.readTree(r.getResponse().getContentAsByteArray());
    }

    private long stored(String branchId) {
        return jdbc.sql("SELECT count(*) FROM branch_diagnostics WHERE branch_id=:b").param("b", branchId).query(Long.class).single();
    }

    @Test
    void snapshotIsTakenStoredAndReturned() {
        var result = take("b01");

        result.assertThat().hasStatusOk();
        var s = body(result);
        assertThat(s.path("id").asString()).isNotBlank();
        assertThat(s.path("branch_id").asString()).isEqualTo("b01");
        assertThat(s.path("taken_at").asString()).isNotBlank();
        assertThat(s.path("requested_by").asString()).isEqualTo(ADMIN_LOGIN);       // from the token, not a header
        assertThat(s.path("payload").path("app").path("branch_id").asString()).isEqualTo("b01");
        assertThat(s.path("payload").path("db").path("error").asString()).startsWith("timed out");
        assertThat(stored("b01")).isEqualTo(1);
    }

    @Test
    void offlineBranchIs503AndNothingIsStored() {
        var result = take("zz9");

        result.assertThat().hasStatus(503);
        assertThat(body(result).path("code").asString()).isEqualTo("BRANCH_UNAVAILABLE");
        assertThat(stored("zz9")).isZero();
    }

    @Test
    void branchErrorIsPassedThrough() throws Exception {
        dispatcher.unsubscribe(Subjects.rpc("b01", "diag.snapshot"));
        var failing = branch.createDispatcher(msg -> branch.publish(msg.getReplyTo(), ("{\"ok\":false,\"status\":500,\"data\":null,"
                + "\"problem\":{\"code\":\"INTERNAL_ERROR\",\"title\":\"t\",\"detail\":\"boom\",\"errors\":[]}}").getBytes(StandardCharsets.UTF_8)));
        failing.subscribe(Subjects.rpc("b01", "diag.snapshot"));
        branch.flush(Duration.ofSeconds(2));

        var result = take("b01");

        result.assertThat().hasStatus(500);
        assertThat(stored("b01")).isZero();
    }

    @Test
    void listHasNoPayloadAndNewestFirst() {
        var first = body(take("b01")).path("id").asString();
        var second = body(take("b01")).path("id").asString();

        var list = body(get("/api/v1/branches/b01/diagnostics/snapshots"));

        assertThat(list).hasSize(2);
        assertThat(list.get(0).path("id").asString()).isEqualTo(second);
        assertThat(list.get(1).path("id").asString()).isEqualTo(first);
        assertThat(list.get(0).has("payload")).isFalse();
        assertThat(list.get(0).path("size_bytes").asInt()).isPositive();
        assertThat(list.get(0).path("requested_by").asString()).isEqualTo(ADMIN_LOGIN);
    }

    @Test
    void snapshotById() {
        var id = body(take("b01")).path("id").asString();

        var s = body(get("/api/v1/diagnostics/snapshots/" + id));

        assertThat(s.path("id").asString()).isEqualTo(id);
        assertThat(s.path("payload").path("jvm").has("deadlocks")).isTrue();
    }

    @Test
    void unknownSnapshotIs404() {
        var result = get("/api/v1/diagnostics/snapshots/" + UUID.randomUUID());

        result.assertThat().hasStatus(404);
        assertThat(body(result).path("code").asString()).isEqualTo("NOT_FOUND");
    }

    @Test
    void requiresAdmin() {
        mvc.post().uri("/api/v1/branches/b01/diagnostics/snapshots").exchange().assertThat().hasStatus(401);
        mvc.get().uri("/api/v1/branches/b01/diagnostics/snapshots").exchange().assertThat().hasStatus(401);
    }

    @Test
    void retentionDropsSnapshotsOlderThan30Days() {
        for (int days : new int[]{31, 29}) {
            jdbc.sql("""
                    INSERT INTO branch_diagnostics(id, branch_id, taken_at, requested_by, size_bytes, payload)
                    VALUES (:id, 'b01', :t, 'network', 2, '{}'::jsonb)""")
                    .param("id", UUID.randomUUID()).param("t", Timestamp.from(Instant.now().minus(Duration.ofDays(days)))).update();
        }

        retention.run();

        assertThat(stored("b01")).isEqualTo(1);
    }
}
