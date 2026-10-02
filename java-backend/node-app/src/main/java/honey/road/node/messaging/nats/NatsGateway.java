package honey.road.node.messaging.nats;

import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.JetStream;
import io.nats.client.JetStreamOptions;
import io.nats.client.Nats;
import io.nats.client.Options;
import honey.road.node.config.NodeProperties;
import honey.road.node.monitoring.Lifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.time.Duration;

/**
 * Node connection to the local NATS leaf.
 * <ul>
 *   <li>Startup does not wait for NATS: async connect with infinite retries; the queue works without the broker
 *       (sect 12), events pile up in the outbox.</li>
 *   <li>{@link #onConnected} listeners run after every (re)connect and create streams and subscriptions idempotently.</li>
 * </ul>
 */
@Component
public class NatsGateway implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(NatsGateway.class);

    private final NatsProperties props;
    private final NodeProperties node;
    private final AtomicReference<Connection> connection = new AtomicReference<>();
    private final List<Consumer<Connection>> onConnected = new CopyOnWriteArrayList<>();
    private volatile String state = "NOT_STARTED";
    private volatile Instant stateSince = Instant.now();
    private volatile boolean running;

    public NatsGateway(NatsProperties props, NodeProperties node) {
        this.props = props;
        this.node = node;
    }

    /** Runs the action on every connect (and right away if already connected). */
    public void onConnected(Consumer<Connection> action) {
        onConnected.add(action);
        var current = connection.get();
        if (current != null && current.getStatus() == Connection.Status.CONNECTED) {
            runSafely(action, current);
        }
    }

    public Optional<Connection> connection() {
        var c = connection.get();
        return c != null && c.getStatus() == Connection.Status.CONNECTED ? Optional.of(c) : Optional.empty();
    }

    public boolean isConnected() {
        return connection().isPresent();
    }

    public JetStream jetStream(Connection c) throws IOException {
        return c.jetStream(JetStreamOptions.builder().requestTimeout(props.publishTimeout()).build());
    }

    public String state() { return state; }

    public Instant stateSince() { return stateSince; }

    public String url() { return props.url(); }

    @Override
    public void start() {
        running = true;
        ConnectionListener listener = (conn, event) -> {
            setState(event.name());
            switch (event) {
                case CONNECTED, RECONNECTED -> {
                    connection.set(conn);
                    Lifecycle.info(log, "nats." + event.name().toLowerCase(), "nats {} url={}",
                            event.name().toLowerCase(), conn.getConnectedUrl());
                    onConnected.forEach(a -> runSafely(a, conn));
                }
                case DISCONNECTED -> Lifecycle.warn(log, "nats.disconnected",
                        "nats disconnected, working autonomously; outbox accumulates");
                case CLOSED -> log.info("nats connection closed");
                default -> log.debug("nats event {}", event);
            }
        };
        var options = Options.builder()
                .server(props.url())
                .connectionName("node-app-" + node.branchId())
                .connectionTimeout(props.connectTimeout())
                .reconnectWait(props.reconnectWait())
                .maxReconnects(-1)
                .connectionListener(listener)
                .build();
        try {
            setState("CONNECTING");
            // true = keep retrying the initial connect until the server shows up.
            Nats.connectAsynchronously(options, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop() {
        running = false;
        var c = connection.getAndSet(null);
        if (c != null) {
            try {
                c.drain(Duration.ofSeconds(5));
            } catch (Exception e) {
                log.debug("nats drain failed", e);
                try { c.close(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
    }

    @Override
    public boolean isRunning() { return running; }

    /** Starts after and stops before the other components. */
    @Override
    public int getPhase() { return Integer.MAX_VALUE - 100; }

    private void setState(String s) {
        state = s;
        stateSince = Instant.now();
    }

    private static void runSafely(Consumer<Connection> action, Connection c) {
        try {
            action.accept(c);
        } catch (RuntimeException e) {
            log.error("nats on-connect action failed", e);
        }
    }
}
