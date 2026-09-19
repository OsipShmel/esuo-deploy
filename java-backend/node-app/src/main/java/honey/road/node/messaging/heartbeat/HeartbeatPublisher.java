package honey.road.node.messaging.heartbeat;

import honey.road.contracts.event.Subjects;
import honey.road.contracts.http.EsuoHeaders;
import honey.road.node.config.JsonCodec;
import honey.road.node.config.NodeProperties;
import honey.road.node.messaging.nats.NatsGateway;
import honey.road.node.messaging.outbox.OutboxWorker;
import honey.road.node.persistence.CatalogRepository;
import honey.road.node.persistence.PolicyRepository;
import honey.road.node.persistence.TicketRepository;
import honey.road.node.persistence.WindowRepository;
import io.nats.client.impl.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Heartbeat {@code branch.{b}.hb} (core NATS, раздел 10.1) — он же регистрация отделения в центре.
 * Первый heartbeat неизвестного отделения центр превращает в запись реестра и source в {@code CENTER_EVT}:
 * новое отделение подключается к сети без ручных действий в центре.
 * <p>Содержит каталог услуг — центр кеширует его для публичной страницы выбора отделения.
 */
@Component
public class HeartbeatPublisher {
    private static final Logger log = LoggerFactory.getLogger(HeartbeatPublisher.class);

    private final NatsGateway nats;
    private final NodeProperties node;
    private final CatalogRepository catalog;
    private final WindowRepository windows;
    private final TicketRepository tickets;
    private final PolicyRepository policies;
    private final OutboxWorker outbox;
    private final JsonCodec json;
    private final Clock clock;

    public HeartbeatPublisher(NatsGateway nats, NodeProperties node, CatalogRepository catalog, WindowRepository windows,
                              TicketRepository tickets, PolicyRepository policies, OutboxWorker outbox, JsonCodec json,
                              Clock clock) {
        this.nats = nats; this.node = node; this.catalog = catalog; this.windows = windows; this.tickets = tickets;
        this.policies = policies; this.outbox = outbox; this.json = json; this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${esuo.nats.heartbeat-interval:10s}", initialDelayString = "2s")
    public void beat() {
        var connection = nats.connection();
        if (connection.isEmpty()) {
            return;
        }
        try {
            var body = json.writeValueAsString(snapshot());
            connection.get().publish(Subjects.heartbeat(node.branchId()),
                    new Headers().add(EsuoHeaders.CONTENT_TYPE, "application/json"),
                    body.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            log.warn("heartbeat failed: {}", e.toString());
        }
    }

    public Map<String, Object> snapshot() {
        var branch = catalog.branch(node.branchId());
        var stats = outbox.stats();
        var allWindows = windows.all(node.branchId());
        var waiting = tickets.waiting(node.branchId());
        var now = clock.instant();
        var hb = new LinkedHashMap<String, Object>();
        hb.put("branch_id", branch.id());
        hb.put("name", branch.name());
        hb.put("timezone", branch.timezone());
        hb.put("sent_at", now);
        // config_version — версия, которую опубликовал центр (по ней центр видит, применена ли его конфигурация);
        // effective_config_version растёт и от локальных изменений руководителя и записывается в талоны.
        var versions = policies.versions();
        hb.put("config_version", versions.central());
        hb.put("effective_config_version", versions.effective());
        hb.put("outbox_pending", stats.pending());
        hb.put("outbox_lag_sec", stats.oldestAge().toSeconds());
        hb.put("windows_total", allWindows.size());
        hb.put("windows_open", allWindows.stream().filter(w -> !"closed".equals(w.status())).count());
        hb.put("waiting", waiting.size());
        hb.put("overdue", waiting.stream().filter(t -> !t.dueAt().isAfter(now)).count());
        hb.put("oldest_wait_sec", waiting.stream().map(t -> java.time.Duration.between(t.issuedAt(), now).toSeconds())
                .max(Long::compare).orElse(0L));
        hb.put("services", catalog.services(node.branchId()).stream().filter(s -> s.active())
                .map(s -> Map.of("id", s.id(), "code", s.code(), "name", s.name(), "number_prefix", s.numberPrefix()))
                .toList());
        return hb;
    }
}
