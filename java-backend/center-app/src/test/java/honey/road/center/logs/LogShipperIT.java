package honey.road.center.logs;

import com.sun.net.httpserver.HttpServer;
import honey.road.center.it.AbstractCenterNatsIT;
import honey.road.center.nats.CenterStreams;
import honey.road.contracts.event.Subjects;
import honey.road.contracts.http.EsuoHeaders;
import io.nats.client.impl.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** LogShipper against a fake Loki (design 5.6): ack only after Loki took the batch, nothing lost while it is down. */
@ExtendWith(OutputCaptureExtension.class)
class LogShipperIT extends AbstractCenterNatsIT {

    record Push(int status, String body) {
    }

    static final List<Push> PUSHES = new CopyOnWriteArrayList<>();
    static final AtomicInteger STATUS = new AtomicInteger(204);
    static final HttpServer LOKI;

    static {
        try {
            LOKI = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        LOKI.createContext("/loki/api/v1/push", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int status = STATUS.get();
            PUSHES.add(new Push(status, body));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        LOKI.start();
    }

    @DynamicPropertySource
    static void loki(DynamicPropertyRegistry registry) {
        registry.add("esuo.center.logs.loki-url", () -> "http://127.0.0.1:" + LOKI.getAddress().getPort());
        registry.add("esuo.center.logs.backoff", () -> "200ms,300ms");
    }

    private static String publish(String marker) throws Exception {
        var line = "{\"@timestamp\":\"2026-10-02T10:00:00Z\",\"log\":{\"level\":\"ERROR\"},\"message\":\"" + marker + "\"}";
        try (var c = testClient()) {
            c.jetStream().publish(Subjects.centerLogPull("b01"), new Headers().add(EsuoHeaders.PULL_ID, "p-test"),
                    line.getBytes(StandardCharsets.UTF_8));
        }
        return marker;
    }

    private static List<Push> pushesWith(String marker) {
        return PUSHES.stream().filter(p -> p.body().contains(marker)).toList();
    }

    private static boolean consumerIdle() throws Exception {
        try (var c = testClient()) {
            var info = c.jetStreamManagement().getConsumerInfo(Subjects.STREAM_CENTER_LOG, LogShipper.DURABLE);
            return info.getNumAckPending() == 0 && info.getNumPending() == 0;
        }
    }

    @Autowired CenterStreams streams;

    @BeforeEach
    void lokiUp() throws Exception {
        await().atMost(Duration.ofSeconds(10)).until(streams::ready);   // CENTER_LOG exists before anything is published
        STATUS.set(204);
        var probe = publish("probe-" + UUID.randomUUID());
        await().atMost(Duration.ofSeconds(15)).until(() -> pushesWith(probe).stream().anyMatch(p -> p.status() == 204));
    }

    @Test
    void shipsBatchAndAcks() throws Exception {
        var a = publish("ship-a-" + UUID.randomUUID());
        var b = publish("ship-b-" + UUID.randomUUID());
        var c = publish("ship-c-" + UUID.randomUUID());

        await().atMost(Duration.ofSeconds(10)).until(() -> !pushesWith(a).isEmpty() && !pushesWith(b).isEmpty() && !pushesWith(c).isEmpty());
        await().atMost(Duration.ofSeconds(10)).until(LogShipperIT::consumerIdle);
        assertThat(pushesWith(a).getFirst().body()).contains("\"source\":\"pull\"").contains("\"pull\":\"p-test\"");
    }

    @Test
    void lokiDownKeepsMessages() throws Exception {
        STATUS.set(503);
        var markers = List.of(publish("down-a-" + UUID.randomUUID()), publish("down-b-" + UUID.randomUUID()),
                publish("down-c-" + UUID.randomUUID()));
        await().atMost(Duration.ofSeconds(10)).until(() -> pushesWith(markers.getFirst()).size() >= 2);

        STATUS.set(204);
        await().atMost(Duration.ofSeconds(15)).until(() -> markers.stream()
                .allMatch(m -> pushesWith(m).stream().anyMatch(p -> p.status() == 204)));
        await().atMost(Duration.ofSeconds(10)).until(LogShipperIT::consumerIdle);
    }

    @Test
    void rejectedBatchIsAckedNotRetried() throws Exception {
        STATUS.set(400);
        var marker = publish("rejected-" + UUID.randomUUID());
        await().atMost(Duration.ofSeconds(10)).until(() -> !pushesWith(marker).isEmpty());
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).until(() -> pushesWith(marker).size() == 1);
        assertThat(consumerIdle()).isTrue();
    }

    @Test
    void lokiDownLogsStateChangeOnce(CapturedOutput output) throws Exception {
        STATUS.set(503);
        var marker = publish("state-" + UUID.randomUUID());
        await().atMost(Duration.ofSeconds(10)).until(() -> pushesWith(marker).size() >= 4);
        STATUS.set(204);
        await().atMost(Duration.ofSeconds(10)).until(() -> pushesWith(marker).stream().anyMatch(p -> p.status() == 204));

        assertThat(output.getOut().lines().filter(l -> l.contains("loki unavailable"))).hasSize(1);
        assertThat(output.getOut().lines().filter(l -> l.contains("loki available again"))).hasSize(1);
    }
}
