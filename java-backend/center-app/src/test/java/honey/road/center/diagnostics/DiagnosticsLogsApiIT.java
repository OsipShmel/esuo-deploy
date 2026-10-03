package honey.road.center.diagnostics;

import honey.road.center.it.AbstractCenterNatsIT;
import honey.road.center.nats.CenterStreams;
import honey.road.contracts.event.Subjects;
import honey.road.contracts.http.EsuoHeaders;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.api.OrderedConsumerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Full branch logs on request (design 5.7): the center pages through {@code diag.logs} and publishes the lines into
 * CENTER_LOG under one pull id. The branch is played by this test.
 */
class DiagnosticsLogsApiIT extends AbstractCenterNatsIT {

    @DynamicPropertySource
    static void grafana(DynamicPropertyRegistry registry) {
        registry.add("esuo.center.logs.grafana-public-url", () -> "http://grafana.test/");
    }

    /** cursor → page the fake branch answers with. */
    static final Map<String, String> PAGES = Map.of(
            "", page(List.of("l1", "l2"), "c1"),
            "c1", page(List.of("l3"), "c2"),
            "c2", page(List.of("l4"), null));

    static String page(List<String> messages, String next) {
        var lines = messages.stream()
                .map(m -> "\"{\\\"@timestamp\\\":\\\"2026-10-02T10:00:00Z\\\",\\\"log\\\":{\\\"level\\\":\\\"INFO\\\"},\\\"message\\\":\\\"" + m + "\\\"}\"")
                .toList();
        return "{\"ok\":true,\"status\":200,\"data\":{\"lines\":[" + String.join(",", lines) + "],\"next_cursor\":"
                + (next == null ? "null" : "\"" + next + "\"") + ",\"truncated\":" + (next != null) + "},\"problem\":null}";
    }

    @Autowired CenterStreams streams;

    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    Connection branch;
    Dispatcher dispatcher;

    @BeforeEach
    void branchOnline() throws Exception {
        await().atMost(Duration.ofSeconds(10)).until(streams::ready);
        branch = testClient();
        dispatcher = branch.createDispatcher(msg -> {
            var body = json.readTree(msg.getData());
            requests.add(body);
            var cursor = body.path("cursor").isNull() ? "" : body.path("cursor").asString("");
            branch.publish(msg.getReplyTo(), PAGES.get(cursor).getBytes(StandardCharsets.UTF_8));
        });
        dispatcher.subscribe(Subjects.rpc("b01", "diag.logs"));
        branch.flush(Duration.ofSeconds(2));
    }

    @AfterEach
    void branchOffline() throws Exception {
        branch.close();
    }

    private MvcTestResult pull(String branchId, String body, boolean withToken) {
        var request = mvc.post().uri("/api/v1/branches/" + branchId + "/diagnostics/logs")
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (withToken) {
            request = request.header("Authorization", "Bearer " + adminToken());
        }
        return request.exchange();
    }

    private JsonNode body(MvcTestResult r) {
        return json.readTree(r.getResponse().getContentAsByteArray());
    }

    private static long linesInCenterLog(String pullId) throws Exception {
        try (var c = testClient()) {
            var consumer = c.getStreamContext(Subjects.STREAM_CENTER_LOG).createOrderedConsumer(
                    new OrderedConsumerConfiguration().filterSubject(Subjects.centerLogPull("b01")));
            long n = 0;
            for (var m = consumer.next(Duration.ofSeconds(1)); m != null; m = consumer.next(Duration.ofSeconds(1))) {
                if (m.getHeaders() != null && pullId.equals(m.getHeaders().getFirst(EsuoHeaders.PULL_ID))) {
                    n++;
                }
            }
            return n;
        }
    }

    @Test
    void pagesUntilCursorEnds() throws Exception {
        var result = pull("b01", "{\"minutes\":30}", true);

        result.assertThat().hasStatusOk();
        var body = body(result);
        assertThat(body.path("lines").asInt()).isEqualTo(4);
        assertThat(body.path("truncated").asBoolean()).isFalse();
        assertThat(body.path("branch_id").asString()).isEqualTo("b01");
        var pullId = body.path("pull_id").asString();
        assertThat(pullId).startsWith("p-");
        assertThat(requests).hasSize(3);
        assertThat(requests.getFirst().path("from").asString()).isNotBlank();
        assertThat(requests.getFirst().path("to").asString()).isNotBlank();
        assertThat(linesInCenterLog(pullId)).isEqualTo(4);
    }

    @Test
    void maxLinesTruncates() throws Exception {
        var body = body(pull("b01", "{\"minutes\":30,\"max_lines\":3}", true));

        assertThat(body.path("lines").asInt()).isEqualTo(3);
        assertThat(body.path("truncated").asBoolean()).isTrue();
        assertThat(linesInCenterLog(body.path("pull_id").asString())).isEqualTo(3);
    }

    @Test
    void offlineBranchIs503() {
        var result = pull("zz9", "{\"minutes\":30}", true);

        result.assertThat().hasStatus(503);
        assertThat(body(result).path("code").asString()).isEqualTo("BRANCH_UNAVAILABLE");
    }

    @Test
    void repeatedPullHasOwnId() throws Exception {
        var first = body(pull("b01", "{\"minutes\":30}", true)).path("pull_id").asString();
        var second = body(pull("b01", "{\"minutes\":30}", true)).path("pull_id").asString();

        assertThat(second).isNotEqualTo(first);
        assertThat(linesInCenterLog(first)).isEqualTo(4);
        assertThat(linesInCenterLog(second)).isEqualTo(4);
    }

    @Test
    void grafanaUrlPointsAtThePull() {
        var body = body(pull("b01", "{\"minutes\":30}", true));

        assertThat(body.path("grafana_url").asString()).startsWith("http://grafana.test/explore?")
                .contains(body.path("pull_id").asString());
    }

    @Test
    void badRangeIsValidation() {
        var result = pull("b01", "{\"from\":\"2026-10-02T11:00:00Z\",\"to\":\"2026-10-02T10:00:00Z\"}", true);

        result.assertThat().hasStatus(400);
    }

    @Test
    void branchErrorIsPassedThrough() throws Exception {
        dispatcher.unsubscribe(Subjects.rpc("b01", "diag.logs"));
        var failing = branch.createDispatcher(msg -> branch.publish(msg.getReplyTo(), ("{\"ok\":false,\"status\":409,\"data\":null,"
                + "\"problem\":{\"code\":\"LOGS_UNAVAILABLE\",\"title\":\"t\",\"detail\":\"no file\",\"errors\":[]}}")
                .getBytes(StandardCharsets.UTF_8)));
        failing.subscribe(Subjects.rpc("b01", "diag.logs"));
        branch.flush(Duration.ofSeconds(2));

        var result = pull("b01", "{\"minutes\":30}", true);

        result.assertThat().hasStatus(409);
        assertThat(body(result).path("code").asString()).isEqualTo("LOGS_UNAVAILABLE");
    }

    @Test
    void requiresAdmin() {
        pull("b01", "{\"minutes\":30}", false).assertThat().hasStatus(401);
    }
}
