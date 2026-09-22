package honey.road.node.demo;

import honey.road.node.messaging.nats.NatsGateway;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /public/demo/status}: whether the branch currently has a live connection to the center. The node
 * only ever talks to the center over its NATS leaf link (sect 12), so that link's state answers the question —
 * no separate probe needed, and no lie during a chaos scenario cut with {@code chaos/cut-branch-link.sh}.
 */
@RestController
class DemoStatusController {
    private final NatsGateway nats;

    DemoStatusController(NatsGateway nats) { this.nats = nats; }

    record Status(boolean centerLink) {
    }

    @GetMapping("/api/v1/public/demo/status")
    Status status() {
        return new Status(nats.isConnected());
    }
}
