package honey.road.node.api.sse;

import honey.road.node.queue.application.OperatorQueries;
import honey.road.node.queue.application.WindowViews;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

/**
 * SSE-потоки ноды. Публичные — под {@code /api/v1/public/**}; операторский и админский требуют JWT.
 * Браузерный {@code EventSource} не умеет заголовки, поэтому для путей {@code .../stream}
 * токен принимается и из параметра {@code access_token} (см. SecurityConfig).
 */
@RestController
public class StreamController {
    private final SseHub hub;
    private final WindowViews windows;
    private final OperatorQueries queue;

    public StreamController(SseHub hub, WindowViews windows, OperatorQueries queue) {
        this.hub = hub;
        this.windows = windows;
        this.queue = queue;
    }

    @GetMapping(path = "/api/v1/public/tickets/{token}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ticket(@PathVariable UUID token) {
        return hub.subscribeTicket(token);
    }

    @GetMapping(path = "/api/v1/public/board/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter board() {
        return hub.subscribeBoard();
    }

    @GetMapping(path = {"/api/v1/operator/stream", "/api/v1/admin/stream"}, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter operator() {
        return hub.subscribeOperator(Map.of("windows", windows.all(), "queue", queue.queue(null, null)));
    }
}
