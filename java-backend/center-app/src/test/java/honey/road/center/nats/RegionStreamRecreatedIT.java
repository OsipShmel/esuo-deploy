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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;

/**
 * A regional leaf whose storage was lost (a reinstalled machine, a recreated container) creates its {@code REGION_EVT}
 * anew, numbered from 1 again. The hub must read the new stream from its start: NATS before 2.15 kept reading from the
 * old position and silently skipped as many events as the old stream had held (nats-server#6206). The same holds for
 * a branch leaf and its {@code BRANCH_EVT}.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RegionStreamRecreatedIT extends AbstractCenterIT {

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

    static final GenericContainer<?> REGION = new GenericContainer<>(AbstractCenterNatsIT.NATS_IMAGE)
            .withNetwork(NET)
            .withCopyToContainer(Transferable.of("""
                    server_name: region-1
                    jetstream { domain: region-1, store_dir: /data }
                    leafnodes { remotes: [ { url: "nats-leaf://hub:7422" } ] }
                    """), "/etc/nats/region.conf")
            .withCommand("-c", "/etc/nats/region.conf")
            .withExposedPorts(4222)
            .waitingFor(Wait.forLogMessage(".*Leafnode connection created.*", 1));

    static {
        HUB.start();
        REGION.start();
    }

    @DynamicPropertySource
    static void hub(DynamicPropertyRegistry registry) {
        registry.add("esuo.center.nats-url", () -> url(HUB));
    }

    private static String url(GenericContainer<?> nats) {
        return "nats://" + nats.getHost() + ":" + nats.getMappedPort(4222);
    }

    @Autowired CenterStreams streams;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void ready() {
        await().atMost(Duration.ofSeconds(10)).until(streams::ready);
    }

    private static void createRegionStream(Connection region) throws Exception {
        region.jetStreamManagement().addStream(StreamConfiguration.builder().name(Subjects.STREAM_REGION_EVT)
                .subjects(Subjects.ALL_BRANCH_EVENTS).storageType(StorageType.File).build());
    }

    /** An event of a regional branch, as the swarm sends it; returns its id. */
    private static UUID publish(Connection region) throws Exception {
        var id = UUID.randomUUID();
        var body = """
                {"event_id":"%s","event_type":"window.opened","branch_id":"rr1","aggregate_type":"window",
                 "aggregate_id":"%s","occurred_at":"2026-10-05T08:00:00Z","data":{}}""".formatted(id, UUID.randomUUID());
        var headers = new Headers().put("Nats-Msg-Id", id.toString());
        region.jetStream().publish(Subjects.branchEvent("rr1", "window.opened"), headers, body.getBytes(StandardCharsets.UTF_8));
        return id;
    }

    /** 2.15 notices a recreated source within about 20 s and reads it from the start; the event waits in the region. */
    private void awaitStored(UUID id) {
        await().atMost(Duration.ofSeconds(60)).until(() ->
                jdbc.sql("SELECT count(*) FROM events WHERE event_id=:id").param("id", id).query(Long.class).single() == 1);
    }

    @Test
    void eventsOfARegionStreamCreatedAnewReachTheCenter() throws Exception {
        try (var region = Nats.connect(url(REGION))) {
            createRegionStream(region);
            await().atMost(Duration.ofSeconds(10)).until(() -> streams.attachRegion("region-1"));
            awaitStored(publish(region));
            awaitStored(publish(region));

            region.jetStreamManagement().deleteStream(Subjects.STREAM_REGION_EVT);
            createRegionStream(region);

            awaitStored(publish(region));                              // number 1 of the new stream: 2.10 never delivered it
        }
    }
}
