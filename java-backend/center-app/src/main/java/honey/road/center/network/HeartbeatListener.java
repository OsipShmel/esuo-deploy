package honey.road.center.network;

import honey.road.center.nats.CenterNats;
import honey.road.center.nats.CenterStreams;
import honey.road.contracts.event.Subjects;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Автоподключение отделений: heartbeat {@code branch.*.hb} → реестр → source в {@code CENTER_EVT}.
 * Новому отделению достаточно знать адрес центра и пароль leaf; в центре ничего не настраивается.
 * Периодическая сверка добирает отделения, у которых подключение source не удалось с первого раза.
 */
@Component
public class HeartbeatListener {
    private static final Logger log = LoggerFactory.getLogger(HeartbeatListener.class);

    private final CenterNats nats;
    private final CenterStreams streams;
    private final BranchRegistry registry;
    private final JsonMapper json;
    private final Clock clock;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Dispatcher dispatcher;

    public HeartbeatListener(CenterNats nats, CenterStreams streams, BranchRegistry registry, JsonMapper json, Clock clock) {
        this.nats = nats; this.streams = streams; this.registry = registry; this.json = json; this.clock = clock;
    }

    @PostConstruct
    void register() {
        nats.onConnected(this::subscribe);
    }

    synchronized void subscribe(Connection c) {
        if (dispatcher != null && dispatcher.isActive()) { return; }
        dispatcher = c.createDispatcher(msg -> workers.execute(() -> handle(msg)));
        dispatcher.subscribe(Subjects.ALL_HEARTBEATS);
        log.info("listening heartbeats on {}", Subjects.ALL_HEARTBEATS);
    }

    void handle(Message msg) {
        var branchId = Subjects.branchIdOf(msg.getSubject());
        if (branchId == null || !branchId.matches("[a-zA-Z0-9_-]{1,64}")) { return; }
        try {
            var body = new String(msg.getData(), StandardCharsets.UTF_8);
            JsonNode hb = json.readTree(body);
            boolean isNew = registry.upsertHeartbeat(branchId, hb.path("name").asString(branchId),
                    hb.path("timezone").asString("Europe/Moscow"), body, clock.instant());
            if (isNew) {
                log.info("new branch registered id={} name={}", branchId, hb.path("name").asString(""));
                attach(branchId);
            }
        } catch (RuntimeException e) {
            log.warn("bad heartbeat from {}: {}", branchId, e.toString());
        }
    }

    /** Сверка: отделения без source (центр рестартовал, NATS был недоступен и т.п.). */
    @Scheduled(fixedDelayString = "PT15S", initialDelayString = "PT5S")
    public void reconcile() {
        if (!streams.ready()) { return; }
        try {
            registry.withoutSource().forEach(this::attach);
        } catch (RuntimeException e) {
            log.warn("reconcile failed: {}", e.toString());
        }
    }

    private void attach(String branchId) {
        if (streams.attachBranch(branchId)) {
            registry.markSourceAttached(branchId);
        }
    }
}
