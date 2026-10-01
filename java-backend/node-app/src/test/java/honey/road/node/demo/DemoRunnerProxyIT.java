package honey.road.node.demo;

import com.sun.net.httpserver.HttpServer;
import honey.road.node.it.AbstractNodeIT;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scenario console proxy (final plan, D5) against a stub runner: the node adds the shared token, passes the runner's
 * answers through and turns a missing runner into a readable 503 instead of a stack trace.
 */
@TestPropertySource(properties = {"esuo.demo-mode=true", "esuo.demo-runner.token=stub-token"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoRunnerProxyIT extends AbstractNodeIT {
    private static final HttpServer RUNNER;
    private static final List<String> SEEN = new CopyOnWriteArrayList<>();

    static {
        try {
            RUNNER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        RUNNER.createContext("/", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            SEEN.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " " + body);
            int status;
            String answer;
            if (!"stub-token".equals(exchange.getRequestHeaders().getFirst("X-Runner-Token"))) {
                status = 401;
                answer = "{\"code\":\"UNAUTHENTICATED\"}";
            } else if (exchange.getRequestURI().getPath().equals("/runs")) {
                status = 409;
                answer = "{\"status\":409,\"code\":\"RUN_IN_PROGRESS\",\"title\":\"Сценарий уже идёт\"}";
            } else {
                status = 200;
                answer = "{\"scenarios\":[{\"code\":\"link-cut\"}]}";
            }
            var bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        RUNNER.start();
    }

    @DynamicPropertySource
    static void runner(DynamicPropertyRegistry registry) {
        registry.add("esuo.demo-runner.url", () -> "http://127.0.0.1:" + RUNNER.getAddress().getPort() + "/");
    }

    @Autowired DemoRunnerController proxy;

    @Test
    @Order(1)
    void scenariosComeFromTheRunnerWithTheSharedToken() {
        var response = proxy.scenarios();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("link-cut");
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(SEEN).contains("GET /scenarios ");               // no 401 from the stub: the token header was sent
    }

    @Test
    @Order(2)
    void runnerProblemsReachTheBrowserAsIs() {
        var response = proxy.start("{\"code\":\"link-cut\",\"args\":{\"fast\":true}}");
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("RUN_IN_PROGRESS");
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(SEEN).contains("POST /runs {\"code\":\"link-cut\",\"args\":{\"fast\":true}}");
    }

    @Test
    @Order(3)
    void stoppedRunnerIsAReadable503() {
        RUNNER.stop(0);
        var response = proxy.current();
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).contains("DEMO_RUNNER_UNAVAILABLE").contains("demo-runner");
    }
}
