package honey.road.node.messaging.rpc;

import honey.road.contracts.event.Subjects;
import honey.road.contracts.http.EsuoHeaders;
import honey.road.contracts.rpc.RpcReply;
import honey.road.node.api.error.Problems;
import honey.road.node.config.JsonCodec;
import honey.road.node.config.NodeProperties;
import honey.road.node.messaging.nats.NatsGateway;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import io.nats.client.impl.Headers;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Center sync requests {@code branch.{b}.rpc.{op}} (sect 4.1, 10.3).
 * <ul>
 *   <li>Queue group {@code node-rpc}: one node instance handles a request.</li>
 *   <li>Handled on virtual threads (JDBC blocks), not on the NATS dispatcher thread.</li>
 *   <li>Expired {@code X-Deadline}: not executed, the center has already answered 503.</li>
 * </ul>
 */
@Component
public class RpcDispatcher {
    private static final Logger log = LoggerFactory.getLogger(RpcDispatcher.class);
    static final String QUEUE_GROUP = "node-rpc";

    private final NatsGateway nats;
    private final NodeProperties node;
    private final RpcOperations operations;
    private final JsonCodec json;
    private final Clock clock;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Dispatcher dispatcher;

    public RpcDispatcher(NatsGateway nats, NodeProperties node, RpcOperations operations, JsonCodec json, Clock clock) {
        this.nats = nats; this.node = node; this.operations = operations; this.json = json; this.clock = clock;
    }

    @PostConstruct
    void register() {
        nats.onConnected(this::subscribe);
    }

    synchronized void subscribe(Connection c) {
        if (dispatcher != null && dispatcher.isActive()) {
            return;                                   // jnats resubscribes after reconnect
        }
        dispatcher = c.createDispatcher(msg -> workers.execute(() -> handle(c, msg)));
        dispatcher.subscribe(Subjects.rpcAll(node.branchId()), QUEUE_GROUP);
        log.info("rpc subscribed subject={} queue={}", Subjects.rpcAll(node.branchId()), QUEUE_GROUP);
    }

    void handle(Connection c, Message msg) {
        var headers = msg.getHeaders();
        var requestId = header(headers, EsuoHeaders.REQUEST_ID);
        if (requestId != null) { MDC.put(EsuoHeaders.MDC_REQUEST_ID, requestId); }
        try {
            var deadline = header(headers, EsuoHeaders.DEADLINE);
            if (deadline != null && Instant.parse(deadline).isBefore(clock.instant())) {
                log.warn("rpc expired before processing subject={}", msg.getSubject());
                return;
            }
            var op = msg.getSubject().substring(Subjects.rpcAll(node.branchId()).length() - 1);
            RpcReply<?> reply;
            try {
                var body = msg.getData() == null || msg.getData().length == 0
                        ? "{}" : new String(msg.getData(), StandardCharsets.UTF_8);
                reply = operations.invoke(op, body, header(headers, EsuoHeaders.IDEMPOTENCY_KEY));
            } catch (RuntimeException e) {
                reply = RpcReply.failed(Problems.from(e));
            }
            if (msg.getReplyTo() != null) {
                var out = new Headers().add(EsuoHeaders.CONTENT_TYPE, "application/json");
                if (requestId != null) { out.add(EsuoHeaders.REQUEST_ID, requestId); }
                c.publish(msg.getReplyTo(), out, json.writeValueAsString(reply).getBytes(StandardCharsets.UTF_8));
            }
            log.info("rpc op={} status={}", op, reply.status());
        } catch (RuntimeException e) {
            log.error("rpc handling failed subject={}", msg.getSubject(), e);
        } finally {
            MDC.remove(EsuoHeaders.MDC_REQUEST_ID);
        }
    }

    private static String header(Headers h, String name) {
        return h == null ? null : h.getFirst(name);
    }
}
