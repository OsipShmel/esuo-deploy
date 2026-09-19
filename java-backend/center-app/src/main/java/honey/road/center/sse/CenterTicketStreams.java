package honey.road.center.sse;

import honey.road.center.gateway.BranchRpcClient;
import honey.road.center.nats.CenterNats;
import honey.road.contracts.error.EsuoException;
import honey.road.contracts.event.Subjects;
import honey.road.contracts.event.TokenHash;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SSE-поток талона для клиента из интернета (9.5, 10.5 {@code center-sse}).
 * <ul>
 *   <li>Снапшот — через RPC {@code ticket.get} к отделению (источник истины).</li>
 *   <li>Живые изменения — core-подписка {@code branch.*.evt.ticket.>} по leaf-соединению; талон клиента
 *       находится по {@code token_hash} — сам токен из отделения не уходит.</li>
 *   <li>Любое изменение очереди отделения обновляет снапшоты подписчиков этого отделения (позиция/ETA).</li>
 *   <li>Отделение недоступно — событие {@code branch_unavailable}; при восстановлении клиент получает снапшот.
 *       Повторная доставка не нужна: снапшот заменяет пропущенные события (9.6).</li>
 * </ul>
 */
@Component
public class CenterTicketStreams {
    private static final Logger log = LoggerFactory.getLogger(CenterTicketStreams.class);
    private static final Duration TIMEOUT = Duration.ofMinutes(30);

    record Subscription(String branchId, UUID token, String tokenHash, SseEmitter emitter) {
    }

    /** branch → подписки */
    private final Map<String, Set<Subscription>> byBranch = new ConcurrentHashMap<>();
    private final ExecutorService fanout = Executors.newVirtualThreadPerTaskExecutor();
    private final CenterNats nats;
    private final BranchRpcClient rpc;
    private final JsonMapper json;
    private volatile Dispatcher dispatcher;

    public CenterTicketStreams(CenterNats nats, BranchRpcClient rpc, JsonMapper json) {
        this.nats = nats; this.rpc = rpc; this.json = json;
    }

    @PostConstruct
    void register() {
        nats.onConnected(this::subscribe);
    }

    synchronized void subscribe(Connection c) {
        if (dispatcher != null && dispatcher.isActive()) { return; }
        dispatcher = c.createDispatcher(msg -> fanout.execute(() -> onEvent(msg)));
        // Только события талонов и окон: они меняют позиции в очереди.
        dispatcher.subscribe("branch.*.evt.ticket.>");
        dispatcher.subscribe("branch.*.evt.window.>");
    }

    public SseEmitter subscribe(String branchId, UUID token) {
        var snapshot = rpc.call(branchId, "ticket.get", Map.of("token", token), null);   // 503/404 — до открытия потока
        if (!snapshot.ok()) {
            throw new TicketStreamRejected(snapshot);
        }
        var emitter = new SseEmitter(TIMEOUT.toMillis());
        var sub = new Subscription(branchId, token, TokenHash.of(token), emitter);
        var group = byBranch.computeIfAbsent(branchId, k -> ConcurrentHashMap.newKeySet());
        group.add(sub);
        emitter.onCompletion(() -> group.remove(sub));
        emitter.onTimeout(() -> group.remove(sub));
        emitter.onError(e -> group.remove(sub));
        send(sub, "snapshot", null, snapshot.data());
        return emitter;
    }

    void onEvent(Message msg) {
        var branchId = Subjects.branchIdOf(msg.getSubject());
        var group = branchId == null ? null : byBranch.get(branchId);
        if (group == null || group.isEmpty()) { return; }
        try {
            var envelope = json.readTree(new String(msg.getData(), StandardCharsets.UTF_8));
            var eventId = envelope.path("event_id").asString(null);
            var hash = envelope.path("data").path("token_hash").asString(null);
            for (var sub : group) {
                if (sub.tokenHash().equals(hash)) {
                    send(sub, envelope.path("event_type").asString("event"), eventId, Map.of(
                            "event_type", envelope.path("event_type").asString(""),
                            "status", envelope.path("data").path("status").asString(""),
                            "window_name", envelope.path("data").path("window_name").asString(""),
                            "occurred_at", envelope.path("occurred_at").asString("")));
                }
                refresh(sub, eventId);
            }
        } catch (RuntimeException e) {
            log.debug("center sse dispatch failed", e);
        }
    }

    private void refresh(Subscription sub, String eventId) {
        try {
            var r = rpc.call(sub.branchId(), "ticket.get", Map.of("token", sub.token()), null);
            if (r.ok()) {
                send(sub, "snapshot", eventId, r.data());
            }
        } catch (EsuoException e) {
            send(sub, "branch_unavailable", null, Map.of("branch_id", sub.branchId()));
        }
    }

    @Scheduled(fixedDelayString = "PT15S")
    public void heartbeat() {
        byBranch.values().forEach(g -> g.forEach(s -> send(s, "heartbeat", null, Map.of())));
    }

    private void send(Subscription sub, String name, String id, Object data) {
        try {
            var event = SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON);
            if (id != null) { event.id(id); }
            synchronized (sub.emitter()) {
                sub.emitter().send(event);
            }
        } catch (IOException | IllegalStateException e) {
            var group = byBranch.get(sub.branchId());
            if (group != null) { group.remove(sub); }
            sub.emitter().completeWithError(e);
        }
    }

    public int connections() {
        return byBranch.values().stream().mapToInt(Set::size).sum();
    }

    /** Отделение ответило ошибкой на снапшот (например, TICKET_NOT_FOUND) — отдаём её как обычный ответ. */
    public static class TicketStreamRejected extends RuntimeException {
        private final transient BranchRpcClient.RpcResult result;

        TicketStreamRejected(BranchRpcClient.RpcResult result) {
            super(null, null, false, false);
            this.result = result;
        }

        public BranchRpcClient.RpcResult result() { return result; }
    }
}
