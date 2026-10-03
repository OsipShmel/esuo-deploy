package honey.road.center.logs;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Counter;
import honey.road.center.config.CenterProperties;
import honey.road.center.nats.CenterNats;
import honey.road.center.nats.CenterStreams;
import honey.road.contracts.event.Subjects;
import io.nats.client.Connection;
import io.nats.client.ConsumerContext;
import io.nats.client.FetchConsumeOptions;
import io.nats.client.Message;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * CENTER_LOG → Loki (design 5.6). A durable pull consumer takes up to {@code batchSize} lines or what came within
 * {@code batchWait}, pushes them in one request and acks only after Loki answered 2xx. Loki down or overloaded: the
 * batch is nak'ed with a delay and waits in CENTER_LOG; Loki refusing the content (400): acked, a retry would not help.
 *
 * <p>Not a {@code DurableWorker}: that one handles messages one by one, and one HTTP request per line would not keep
 * up with a network catching up after an outage.
 */
@Component
public class LogShipper implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(LogShipper.class);
    static final String DURABLE = "loki-shipper";
    private static final Duration ACK_WAIT = Duration.ofSeconds(60);
    private static final Duration IDLE = Duration.ofSeconds(1);

    private final CenterNats nats;
    private final CenterStreams streams;
    private final LokiClient loki;
    private final CenterProperties.Logs props;
    private final Clock clock;

    private volatile boolean running;
    private volatile Thread worker;
    private Connection boundTo;
    private ConsumerContext consumer;
    private Boolean lokiUp;                                      // null until the first push
    private int failures;

    private final Counter shipped;
    private final Counter dropped;

    public LogShipper(CenterNats nats, CenterStreams streams, LokiClient loki, CenterProperties props, Clock clock,
                      MeterRegistry meters) {
        this.nats = nats; this.streams = streams; this.loki = loki; this.props = props.logs(); this.clock = clock;
        this.shipped = Counter.builder("esuo.logs.shipped").description("Branch log lines shipped to Loki").register(meters);
        this.dropped = Counter.builder("esuo.logs.dropped").description("Branch log lines Loki rejected").register(meters);
        Gauge.builder("esuo.logs.loki.up", this, s -> Boolean.TRUE.equals(s.lokiUp) ? 1 : 0)
                .description("1 when the last push to Loki went through").register(meters);
    }

    @Override
    public void start() {
        if (props.lokiUrl().isEmpty()) {
            log.info("loki shipping is off (LOKI_URL is empty), branch logs wait in {}", Subjects.STREAM_CENTER_LOG);
            return;
        }
        running = true;
        worker = Thread.ofVirtual().name("loki-shipper").start(this::loop);
        log.info("loki shipping to {}", props.lokiUrl());
    }

    @Override
    public void stop() {
        running = false;
        var t = worker;
        if (t != null) {
            t.interrupt();
            try {
                t.join(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() { return running; }

    /** After CenterNats: the loop only needs the connection to exist eventually, it waits for it on its own. */
    @Override
    public int getPhase() { return Integer.MAX_VALUE - 50; }

    private void loop() {
        while (running) {
            try {
                var context = consumer();
                if (context == null) {
                    pause(IDLE);
                    continue;
                }
                var batch = fetch(context);
                if (!batch.isEmpty()) {
                    ship(batch);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                log.warn("log shipping pass failed: {}", e.toString());
                consumer = null;                                 // re-bind on the next pass
                pauseQuietly(IDLE);
            }
        }
    }

    /** The consumer of the current connection; re-created after a reconnect. {@code null} while the hub is away. */
    private ConsumerContext consumer() throws Exception {
        var connection = nats.connection().orElse(null);
        if (connection == null || !streams.ready()) {
            return null;
        }
        if (connection != boundTo || consumer == null) {
            var config = ConsumerConfiguration.builder()
                    .durable(DURABLE)
                    .ackPolicy(AckPolicy.Explicit)
                    .ackWait(ACK_WAIT)
                    .build();
            connection.jetStreamManagement().addOrUpdateConsumer(Subjects.STREAM_CENTER_LOG, config);
            consumer = connection.getConsumerContext(Subjects.STREAM_CENTER_LOG, DURABLE);
            boundTo = connection;
        }
        return consumer;
    }

    private List<Message> fetch(ConsumerContext context) throws Exception {
        var options = FetchConsumeOptions.builder().maxMessages(props.batchSize())
                .expiresIn(props.batchWait().toMillis()).build();
        var batch = new ArrayList<Message>();
        try (var fetch = context.fetch(options)) {
            for (var m = fetch.nextMessage(); m != null; m = fetch.nextMessage()) {
                batch.add(m);
            }
        }
        return batch;
    }

    private void ship(List<Message> batch) throws InterruptedException {
        var records = new ArrayList<LogRecord>(batch.size());
        for (var m : batch) {
            var record = LogRecords.of(m.getSubject(), m.getHeaders(), m.getData(), timestamp(m));
            if (record.isPresent()) {
                records.add(record.get());
            } else {
                log.warn("not a log subject in {}: {}, dropped", Subjects.STREAM_CENTER_LOG, m.getSubject());
                m.ack();
            }
        }
        if (records.isEmpty()) {
            return;
        }
        var result = loki.push(LokiPush.payload(records));
        switch (result.outcome()) {
            case OK -> {
                batch.forEach(Message::ack);
                shipped.increment(records.size());
                if (Boolean.FALSE.equals(lokiUp)) {
                    log.info("loki available again, shipping resumed");
                }
                lokiUp = true;
                failures = 0;
            }
            case REJECTED -> {
                batch.forEach(Message::ack);
                dropped.increment(records.size());
                log.warn("loki rejected a batch of {} lines, dropped: status={} {}", records.size(), result.status(),
                        result.detail());
                lokiUp = true;
                failures = 0;
            }
            case RETRY -> {
                failures++;
                var delay = props.backoff().get(Math.min(failures, props.backoff().size()) - 1);
                batch.forEach(m -> m.nakWithDelay(delay));
                if (!Boolean.FALSE.equals(lokiUp)) {
                    log.warn("loki unavailable (status={} {}), branch logs wait in {}", result.status(), result.detail(),
                            Subjects.STREAM_CENTER_LOG);
                }
                lokiUp = false;
                pause(delay);
            }
        }
    }

    private java.time.Instant timestamp(Message m) {
        try {
            return m.metaData().timestamp().toInstant();
        } catch (RuntimeException e) {
            return clock.instant();
        }
    }

    private static void pause(Duration d) throws InterruptedException {
        Thread.sleep(d);
    }

    private static void pauseQuietly(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
