package honey.road.center.nats;

import honey.road.center.config.CenterProperties;
import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Подключение центра к NATS hub. Асинхронное, с бесконечным переподключением, как у ноды. */
@Component
public class CenterNats implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(CenterNats.class);

    private final CenterProperties props;
    private final AtomicReference<Connection> connection = new AtomicReference<>();
    private final List<Consumer<Connection>> onConnected = new CopyOnWriteArrayList<>();
    private volatile String state = "NOT_STARTED";
    private volatile boolean running;

    public CenterNats(CenterProperties props) { this.props = props; }

    public void onConnected(Consumer<Connection> action) {
        onConnected.add(action);
        connection().ifPresent(c -> runSafely(action, c));
    }

    public Optional<Connection> connection() {
        var c = connection.get();
        return c != null && c.getStatus() == Connection.Status.CONNECTED ? Optional.of(c) : Optional.empty();
    }

    public String state() { return state; }

    @Override
    public void start() {
        running = true;
        ConnectionListener listener = (conn, event) -> {
            state = event.name();
            switch (event) {
                case CONNECTED, RECONNECTED -> {
                    connection.set(conn);
                    log.info("nats {} url={}", event.name().toLowerCase(), conn.getConnectedUrl());
                    onConnected.forEach(a -> runSafely(a, conn));
                }
                case DISCONNECTED -> log.warn("nats hub disconnected");
                default -> log.debug("nats event {}", event);
            }
        };
        var options = Options.builder()
                .server(props.natsUrl())
                .connectionName("center-app")
                .connectionTimeout(Duration.ofSeconds(3))
                .reconnectWait(Duration.ofSeconds(1))
                .maxReconnects(-1)
                .connectionListener(listener)
                .build();
        try {
            state = "CONNECTING";
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
            try { c.drain(Duration.ofSeconds(5)); } catch (Exception e) { log.debug("drain failed", e); }
        }
    }

    @Override
    public boolean isRunning() { return running; }

    @Override
    public int getPhase() { return Integer.MAX_VALUE - 100; }

    private static void runSafely(Consumer<Connection> action, Connection c) {
        try {
            action.accept(c);
        } catch (RuntimeException e) {
            log.error("nats on-connect action failed", e);
        }
    }
}
