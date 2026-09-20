package honey.road.center.nats;

import honey.road.contracts.event.Subjects;
import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.MessageConsumer;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.List;

/**
 * Durable pull consumer on {@code CENTER_EVT} with a subject filter (sect 10.5). Starts on every NATS connect and
 * is re-checked periodically, so the center works while the hub is down and catches up afterwards.
 * <p>Retries are done by JetStream: {@link #retryLater} naks with the delay for the current delivery attempt,
 * the consumer {@code backoff} covers redelivery after a crash (no ack at all).
 */
public abstract class DurableWorker {
    private final Logger log = LoggerFactory.getLogger(getClass());

    private final CenterNats nats;
    private final CenterStreams streams;
    private final String durable;
    private final String filter;
    private volatile MessageConsumer consumer;

    protected DurableWorker(CenterNats nats, CenterStreams streams, String durable, String filter) {
        this.nats = nats; this.streams = streams; this.durable = durable; this.filter = filter;
    }

    /** Delivery policy, max_deliver, backoff etc. on top of durable, filter and explicit ack. */
    protected abstract ConsumerConfiguration.Builder configure(ConsumerConfiguration.Builder builder);

    /** Must ack, nak or term the message; an exception naks it with the retry delay. */
    protected abstract void handle(Message msg) throws Exception;

    /** Delays between attempts; the last one repeats. Empty means 5 s. */
    protected List<Duration> backoff() {
        return List.of();
    }

    @PostConstruct
    void register() {
        nats.onConnected(this::start);
    }

    synchronized void start(Connection c) {
        if (consumer != null || !streams.ready()) { return; }
        try {
            var config = configure(ConsumerConfiguration.builder()
                    .durable(durable)
                    .filterSubject(filter)
                    .ackPolicy(AckPolicy.Explicit)).build();
            c.jetStreamManagement().addOrUpdateConsumer(Subjects.STREAM_CENTER_EVT, config);
            consumer = c.getConsumerContext(Subjects.STREAM_CENTER_EVT, durable).consume(this::dispatch);
            log.info("{} consuming {} [{}]", durable, Subjects.STREAM_CENTER_EVT, filter);
        } catch (Exception e) {
            log.error("cannot start consumer {}", durable, e);
        }
    }

    private void dispatch(Message msg) {
        try {
            handle(msg);
        } catch (Exception e) {
            log.warn("{}: handling failed, will be redelivered: {}", durable, e.toString());
            retryLater(msg);
        }
    }

    protected void retryLater(Message msg) {
        msg.nakWithDelay(delayFor(deliveredCount(msg)));
    }

    protected Duration delayFor(long delivered) {
        var steps = backoff();
        if (steps.isEmpty()) { return Duration.ofSeconds(5); }
        return steps.get((int) Math.min(Math.max(delivered, 1), steps.size()) - 1);
    }

    protected static long deliveredCount(Message msg) {
        try {
            return msg.metaData().deliveredCount();
        } catch (RuntimeException e) {
            return 1;
        }
    }

    @Scheduled(fixedDelayString = "PT10S", initialDelayString = "PT10S")
    public void ensureStarted() {
        if (consumer == null) {
            nats.connection().ifPresent(this::start);
        }
    }

    public boolean running() { return consumer != null; }

    @PreDestroy
    void stop() {
        var c = consumer;
        if (c != null) {
            try { c.stop(); } catch (RuntimeException ignored) { }
        }
    }
}
