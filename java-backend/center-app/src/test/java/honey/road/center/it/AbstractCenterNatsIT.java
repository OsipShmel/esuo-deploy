package honey.road.center.it;

import io.nats.client.Connection;
import io.nats.client.Nats;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;

/** Center tests that need the hub: streams, durable consumers, request-reply to a branch played by the test. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)   // one live context per broker: no foreign subscribers
public abstract class AbstractCenterNatsIT extends AbstractCenterIT {

    static final GenericContainer<?> HUB = new GenericContainer<>("nats:2.10.26-alpine")
            .withCommand("-js")
            .withExposedPorts(4222)
            .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

    static {
        HUB.start();
    }

    protected static String hubUrl() {
        return "nats://" + HUB.getHost() + ":" + HUB.getMappedPort(4222);
    }

    @DynamicPropertySource
    static void hub(DynamicPropertyRegistry registry) {
        registry.add("esuo.center.nats-url", AbstractCenterNatsIT::hubUrl);
    }

    protected static Connection testClient() throws IOException, InterruptedException {
        return Nats.connect(hubUrl());
    }
}
