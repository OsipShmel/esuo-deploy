package honey.road.center.nats;

import honey.road.center.it.AbstractCenterIT;
import honey.road.center.it.AbstractCenterNatsIT;
import honey.road.contracts.event.Subjects;
import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.impl.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;

/**
 * The regional topology of the swarm (design doc 3.8) in containers: the hub of the center and two regional leafs,
 * JetStream domains {@code region-1} and {@code region-2}, each with its own {@code REGION_EVT}.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class AbstractRegionsIT extends AbstractCenterIT {

    static final Network NET = Network.newNetwork();

    static final GenericContainer<?> HUB = new GenericContainer<>(AbstractCenterNatsIT.NATS_IMAGE)
            .withNetwork(NET).withNetworkAliases("hub")
            .withCopyToContainer(Transferable.of("""
                    server_name: hub
                    jetstream { domain: center, store_dir: /data }
                    leafnodes { port: 7422 }
                    """), "/etc/nats/hub.conf")
            .withCommand("-c", "/etc/nats/hub.conf")
            .withExposedPorts(4222)
            .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

    static final GenericContainer<?> REGION_1 = region("region-1");
    static final GenericContainer<?> REGION_2 = region("region-2");

    private static GenericContainer<?> region(String domain) {
        return new GenericContainer<>(AbstractCenterNatsIT.NATS_IMAGE)
                .withNetwork(NET)
                .withCopyToContainer(Transferable.of("""
                        server_name: %s
                        jetstream { domain: %s, store_dir: /data }
                        leafnodes { remotes: [ { url: "nats-leaf://hub:7422" } ] }
                        """.formatted(domain, domain)), "/etc/nats/region.conf")
                .withCommand("-c", "/etc/nats/region.conf")
                .withExposedPorts(4222)
                .waitingFor(Wait.forLogMessage(".*Leafnode connection created.*", 1));
    }

    static {
        HUB.start();
        REGION_1.start();
        REGION_2.start();
    }

    @DynamicPropertySource
    static void hub(DynamicPropertyRegistry registry) {
        registry.add("esuo.center.nats-url", () -> url(HUB));
    }

    static String url(GenericContainer<?> nats) {
        return "nats://" + nats.getHost() + ":" + nats.getMappedPort(4222);
    }

    static Connection connect(GenericContainer<?> nats) throws IOException, InterruptedException {
        return Nats.connect(url(nats));
    }

    @Autowired CenterStreams streams;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void ready() {
        await().atMost(Duration.ofSeconds(10)).until(streams::ready);
    }

    static void createRegionStream(Connection region) throws Exception {
        region.jetStreamManagement().addStream(StreamConfiguration.builder().name(Subjects.STREAM_REGION_EVT)
                .subjects(Subjects.ALL_BRANCH_EVENTS).storageType(StorageType.File).build());
    }

    /** An event of a regional branch, as the swarm sends it; returns its id once the region has stored it. */
    static UUID publish(Connection region, String branch) throws Exception {
        var id = UUID.randomUUID();
        var body = """
                {"event_id":"%s","event_type":"window.opened","branch_id":"%s","aggregate_type":"window",
                 "aggregate_id":"%s","occurred_at":"2026-10-05T08:00:00Z","data":{}}""".formatted(id, branch, UUID.randomUUID());
        var headers = new Headers().put("Nats-Msg-Id", id.toString());
        region.jetStream().publish(Subjects.branchEvent(branch, "window.opened"), headers, body.getBytes(StandardCharsets.UTF_8));
        return id;
    }

    long stored(String branch) {
        return jdbc.sql("SELECT count(*) FROM events WHERE branch_id=:b").param("b", branch).query(Long.class).single();
    }

    /** 2.15 notices a recreated source within about 20 s and reads it from the start; the event waits in the region. */
    void awaitStored(UUID id) {
        await().atMost(Duration.ofSeconds(60)).until(() ->
                jdbc.sql("SELECT count(*) FROM events WHERE event_id=:id").param("id", id).query(Long.class).single() == 1);
    }
}
