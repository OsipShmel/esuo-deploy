package honey.road.node.it;

import io.nats.client.Connection;
import io.nats.client.Nats;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;

/**
 * Node tests that need a real broker (streams, request-reply). {@link AbstractNodeIT} stays without NATS on purpose —
 * it proves the branch works alone; this base adds a JetStream-enabled server as the branch leaf.
 */
public abstract class AbstractNatsNodeIT extends AbstractNodeIT {

    static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.10.26-alpine")
            .withCommand("-js")
            .withExposedPorts(4222)
            .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

    static {
        NATS.start();
    }

    protected static String natsUrl() {
        return "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222);
    }

    @DynamicPropertySource
    static void nats(DynamicPropertyRegistry registry) {
        registry.add("esuo.nats.url", AbstractNatsNodeIT::natsUrl);
        registry.add("esuo.nats.leaf-monitor-url", () -> "http://127.0.0.1:1");   // no leaf monitor in tests
    }

    /** A separate client connection, as the center or a collector would have. */
    protected static Connection testClient() throws IOException, InterruptedException {
        return Nats.connect(natsUrl());
    }
}
