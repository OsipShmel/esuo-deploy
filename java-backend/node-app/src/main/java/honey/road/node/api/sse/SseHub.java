package honey.road.node.api.sse;

import honey.road.contracts.event.EventEnvelope;
import honey.road.contracts.event.EventTypes;
import honey.road.node.messaging.DomainEventCommitted;
import honey.road.node.queue.application.BoardQueries;
import honey.road.node.api.publicapi.QrCodes;
import honey.road.node.queue.application.TicketQueries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
    * SSE within a node (section 9.6). Source - {@link DomainEventCommitted} after transaction commit.
    * <ul>
    * <li>{@code ticket}: subscribers of your ticket. If there is any change in the department queue, each person waiting
    * receives a new {@code snapshot} (position and ETA recalculated), when changing his ticket -
    * also the event itself.</li>
    * <li>{@code board}: scoreboard - snapshot of the scoreboard during calls.</li>
    * <li>{@code operator}: AWS and admin panel - all department events as is (envelope 10.2).</li>
    * </ul>
    * Each message: {@code id} = event_id, {@code event} = event_type. First message - {@code snapshot},
    * every 15 s - {@code heartbeat}. Reconnect with {@code Last-Event-ID} = new snapshot.
 */
@Component
public class SseHub {
    private static final Logger log = LoggerFactory.getLogger(SseHub.class);
    static final Duration TIMEOUT = Duration.ofMinutes(30);
    private static final Set<String> BOARD_EVENTS = Set.of(EventTypes.TICKET_CALLED, EventTypes.TICKET_RECALLED,
            EventTypes.TICKET_SERVING, EventTypes.TICKET_COMPLETED, EventTypes.TICKET_NO_SHOW, EventTypes.TICKET_REQUEUED,
            EventTypes.TICKET_REDIRECTED, EventTypes.TICKET_CREATED, EventTypes.TICKET_CANCELLED, EventTypes.TICKET_CHECKED_IN,
            EventTypes.WINDOW_OPENED, EventTypes.WINDOW_CLOSED);

    private final Map<UUID, Set<SseEmitter>> tickets = new ConcurrentHashMap<>();
    private final Set<SseEmitter> boards = ConcurrentHashMap.newKeySet();
    private final Set<SseEmitter> operators = ConcurrentHashMap.newKeySet();
    private final ExecutorService fanout = Executors.newVirtualThreadPerTaskExecutor();

    private final TicketQueries ticketQueries;
    private final BoardQueries boardQueries;
    private final QrCodes qr;

    public SseHub(TicketQueries ticketQueries, BoardQueries boardQueries, QrCodes qr) {
        this.ticketQueries = ticketQueries;
        this.boardQueries = boardQueries;
        this.qr = qr;
    }

    // --------------- подписка

    public SseEmitter subscribeTicket(UUID token) {
        var snapshot = ticketQueries.get(token);                 // 404 до открытия потока
        var emitter = new SseEmitter(TIMEOUT.toMillis());
        tickets.computeIfAbsent(token, k -> ConcurrentHashMap.newKeySet()).add(emitter);
        emitter.onCompletion(() -> remove(tickets.get(token), emitter));
        emitter.onTimeout(() -> remove(tickets.get(token), emitter));
        emitter.onError(e -> remove(tickets.get(token), emitter));
        send(emitter, "snapshot", null, snapshot);
        return emitter;
    }

    public SseEmitter subscribeBoard() {
        return register(boards, boardQueries.board(qr.current()));
    }

    public SseEmitter subscribeOperator(Object snapshot) {
        return register(operators, snapshot);
    }

    private SseEmitter register(Set<SseEmitter> group, Object snapshot) {
        var emitter = new SseEmitter(TIMEOUT.toMillis());
        group.add(emitter);
        emitter.onCompletion(() -> group.remove(emitter));
        emitter.onTimeout(() -> group.remove(emitter));
        emitter.onError(e -> group.remove(emitter));
        send(emitter, "snapshot", null, snapshot);
        return emitter;
    }

    // --------------- рассылка

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCommitted(DomainEventCommitted event) {
        fanout.execute(() -> dispatch(event));
    }

    void dispatch(DomainEventCommitted event) {
        var envelope = event.envelope();
        broadcast(operators, envelope.eventType(), envelope.eventId().toString(), envelope);

        if (!EventTypes.AGGREGATE_TICKET.equals(envelope.aggregateType())
                && !EventTypes.AGGREGATE_WINDOW.equals(envelope.aggregateType())) {
            return;
        }
        // Изменился свой талон — событие + снапшот; изменилась очередь — снапшоты всем подписчикам.
        tickets.forEach((token, emitters) -> {
            if (emitters.isEmpty()) { return; }
            try {
                var view = ticketQueries.get(token);
                if (token.equals(event.publicToken())) {
                    broadcast(emitters, envelope.eventType(), envelope.eventId().toString(), publicEvent(envelope));
                }
                broadcast(emitters, "snapshot", envelope.eventId().toString(), view);
            } catch (RuntimeException e) {
                log.debug("ticket snapshot failed", e);
            }
        });
        if (BOARD_EVENTS.contains(envelope.eventType()) && !boards.isEmpty()) {
            broadcast(boards, "snapshot", envelope.eventId().toString(), boardQueries.board(qr.current()));
        }
    }

    private static Map<String, Object> publicEvent(EventEnvelope e) {
        var data = e.data();
        return Map.of("event_id", e.eventId(), "event_type", e.eventType(), "occurred_at", e.occurredAt(),
                "status", String.valueOf(data.get("status")),
                "window_name", data.get("window_name") == null ? "" : data.get("window_name"),
                "call_count", data.get("call_count") == null ? 0 : data.get("call_count"));
    }

    @Scheduled(fixedDelayString = "PT15S")
    public void heartbeat() {
        tickets.values().forEach(g -> broadcast(g, "heartbeat", null, Map.of()));
        broadcast(boards, "heartbeat", null, Map.of());
        broadcast(operators, "heartbeat", null, Map.of());
        tickets.values().removeIf(Set::isEmpty);
    }

    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        tickets.values().forEach(g -> g.forEach(e -> close(e)));
        boards.forEach(SseHub::close);
        operators.forEach(SseHub::close);
        fanout.shutdown();
    }

    public int connections() {
        return tickets.values().stream().mapToInt(Set::size).sum() + boards.size() + operators.size();
    }

    // --------------- низкий уровень

    private static void close(SseEmitter e) {
        try {
            e.send(SseEmitter.event().name("reconnect").data(Map.of(), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ignored) {
            // клиент уже ушёл
        }
        e.complete();
    }

    private void broadcast(Set<SseEmitter> group, String name, String id, Object data) {
        if (group == null) { return; }
        for (var e : group) {
            if (!send(e, name, id, data)) {
                group.remove(e);
            }
        }
    }

    private static void remove(Set<SseEmitter> group, SseEmitter e) {
        if (group != null && e != null) { group.remove(e); }
    }

    private static boolean send(SseEmitter e, String name, String id, Object data) {
        try {
            var event = SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON);
            if (id != null) { event.id(id); }
            synchronized (e) {
                e.send(event);
            }
            return true;
        } catch (IOException | IllegalStateException ex) {
            e.completeWithError(ex);
            return false;
        }
    }
}
