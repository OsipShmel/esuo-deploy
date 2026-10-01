package honey.road.node.demo;

import honey.road.contracts.error.ErrorCode;
import honey.road.contracts.error.EsuoException;
import honey.road.node.config.NodeProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Scenario console of the demo page: a thin proxy to {@code tools/demo-runner}. The runner holds the Docker socket,
 * so it is never published; the browser reaches it only through the node, as a signed-in branch admin, and the node
 * adds the shared token. Works with {@code DEMO_MODE=true} and a configured runner address only.
 */
@RestController
@RequestMapping("/api/v1/admin/demo/runner")
class DemoRunnerController {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String UNAVAILABLE = """
            {"type":"about:blank","status":503,"code":"DEMO_RUNNER_UNAVAILABLE","title":"Пульт сценариев не запущен",\
            "detail":"Сервис demo-runner не отвечает: docker compose --profile demo up -d demo-runner"}""";

    private final NodeProperties node;
    private final DemoRunnerProperties props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    DemoRunnerController(NodeProperties node, DemoRunnerProperties props) {
        this.node = node;
        this.props = props;
    }

    @GetMapping("/scenarios")
    ResponseEntity<String> scenarios() {
        return forward(request("/scenarios").GET());
    }

    @GetMapping("/runs/current")
    ResponseEntity<String> current() {
        return forward(request("/runs/current").GET());
    }

    /** Body: {@code {"code": "link-cut", "args": {"fast": true}}}; the runner validates it. */
    @PostMapping("/runs")
    ResponseEntity<String> start(@RequestBody String body) {
        return forward(request("/runs").header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
    }

    @PostMapping("/runs/current/stop")
    ResponseEntity<String> stop() {
        return forward(request("/runs/current/stop").POST(HttpRequest.BodyPublishers.noBody()));
    }

    /**
     * Live output: the runner's SSE is relayed byte for byte ({@code snapshot}, {@code line}, {@code state},
     * {@code heartbeat}). Written straight to the response on the request (virtual) thread: an async body would be
     * cut by the container's async timeout every 30 s.
     */
    @GetMapping("/stream")
    void stream(HttpServletResponse response) throws IOException {
        HttpResponse<InputStream> upstream;
        try {
            upstream = http.send(request("/runs/current/stream").timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.setStatus(503);
            return;
        } catch (IOException e) {
            response.setStatus(503);
            return;
        }
        try (var in = upstream.body()) {
            if (upstream.statusCode() != 200) {
                response.setStatus(503);
                return;
            }
            response.setStatus(200);
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
            response.setHeader("X-Accel-Buffering", "no");
            var out = response.getOutputStream();
            var buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) >= 0; ) {
                out.write(buffer, 0, n);
                out.flush();                                     // every SSE message reaches the browser at once
            }
        } catch (IOException e) {
            // the browser closed the tab or the runner restarted: EventSource reconnects on its own
        }
    }

    private HttpRequest.Builder request(String path) {
        if (!node.demoMode() || props.url().isEmpty()) {
            throw new EsuoException(ErrorCode.NOT_FOUND, "Пульт сценариев не подключён (нужны DEMO_MODE=true и DEMO_RUNNER_URL)");
        }
        return HttpRequest.newBuilder(URI.create(props.url() + path)).header("X-Runner-Token", props.token());
    }

    /** Status and JSON body of the runner go to the browser as is: its errors are already problem+json with {@code code}. */
    private ResponseEntity<String> forward(HttpRequest.Builder request) {
        try {
            var response = http.send(request.timeout(TIMEOUT).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            // A runner started with another token is a stand misconfiguration, not an expired admin session.
            int status = response.statusCode() == 401 ? 502 : response.statusCode();
            return ResponseEntity.status(status)
                    .contentType(status < 400 ? MediaType.APPLICATION_JSON : MediaType.APPLICATION_PROBLEM_JSON)
                    .body(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EsuoException(ErrorCode.INTERNAL_ERROR, "Прервано");
        } catch (IOException e) {
            return ResponseEntity.status(503).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(UNAVAILABLE);
        }
    }
}
