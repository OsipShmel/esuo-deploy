package honey.road.node.messaging.inbox;

import honey.road.contracts.event.Subjects;
import honey.road.contracts.http.EsuoHeaders;
import honey.road.node.config.NodeProperties;
import honey.road.node.messaging.nats.BranchStreams;
import honey.road.node.messaging.nats.NatsGateway;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * {@code node-inbox} consumer (sect 10.5): durable pull on {@code BRANCH_INBOX}, {@code ack_explicit},
 * {@code max_deliver=10}, backoff 1 s → 5 min. Message handling lives in {@link BranchInboxProcessor}.
 * <ul>
 *   <li>Works without NATS: the loop waits for the connection and the stream.</li>
 *   <li>Dedup id is {@code Nats-Msg-Id}, falling back to the stream sequence.</li>
 *   <li>Success or permanent failure → {@code ack}; transient failure → delayed {@code nak}.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "esuo.inbox.enabled", havingValue = "true", matchIfMissing = true)
public class BranchInboxConsumer {
    private static final Logger log = LoggerFactory.getLogger(BranchInboxConsumer.class);
    static final String DURABLE = "node-inbox";
    private static final int BATCH = 10;
    private static final Duration FETCH_WAIT = Duration.ofSeconds(2);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);

    private final NatsGateway nats;
    private final BranchStreams streams;
    private final BranchInboxProcessor processor;
    private final NodeProperties node;
    private volatile boolean running;
    private volatile Thread worker;

    public BranchInboxConsumer(NatsGateway nats, BranchStreams streams, BranchInboxProcessor processor, NodeProperties node) {
        this.nats = nats; this.streams = streams; this.processor = processor; this.node = node;
    }

    @PostConstruct
    void start() {
        running = true;
        worker = Thread.ofVirtual().name("node-inbox").start(this::loop);
    }

    @PreDestroy
    void stop() {
        running = false;
        var t = worker;
        if (t != null) { t.interrupt(); }
    }

    private void loop() {
        JetStreamSubscription sub = null;
        while (running) {
            try {
                if (!nats.isConnected() || !streams.branchInboxReady()) {
                    sub = null;
                    sleep(Duration.ofSeconds(1));
                    continue;
                }
                if (sub == null) {
                    sub = subscribe();
                    log.info("inbox consumer {} ready on {}", DURABLE, Subjects.STREAM_BRANCH_INBOX);
                }
                for (var msg : sub.fetch(BATCH, FETCH_WAIT)) {
                    handle(msg);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("inbox consumer error, will resubscribe: {}", e.toString());
                sub = null;
                sleepQuietly(Duration.ofSeconds(2));
            }
        }
    }

    private JetStreamSubscription subscribe() throws Exception {
        var c = nats.connection().orElseThrow(() -> new IllegalStateException("nats is not connected"));
        var config = ConsumerConfiguration.builder()
                .durable(DURABLE)
                .ackPolicy(AckPolicy.Explicit)
                .maxDeliver(10)
                .backoff(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30),
                        Duration.ofMinutes(2), Duration.ofMinutes(5))
                .filterSubjects(List.of(Subjects.CONFIG_GLOBAL, Subjects.configBranch(node.branchId()),
                        Subjects.mpInbox(node.branchId())))
                .build();
        var options = PullSubscribeOptions.builder().stream(Subjects.STREAM_BRANCH_INBOX).configuration(config).build();
        return nats.jetStream(c).subscribe(null, options);
    }

    private void handle(Message msg) {
        try {
            var outcome = processor.process(msg.getSubject(), messageId(msg),
                    new String(msg.getData(), StandardCharsets.UTF_8));
            log.info("inbox message subject={} outcome={}", msg.getSubject(), outcome);
            msg.ack();
        } catch (Exception e) {
            log.error("inbox message failed, will be redelivered subject={}", msg.getSubject(), e);
            msg.nakWithDelay(RETRY_DELAY);
        }
    }

    private static String messageId(Message msg) {
        var headers = msg.getHeaders();
        var id = headers == null ? null : headers.getFirst(EsuoHeaders.NATS_MSG_ID);
        return id != null && !id.isBlank() ? id : Subjects.STREAM_BRANCH_INBOX + ":" + msg.metaData().streamSequence();
    }

    private static void sleep(Duration d) throws InterruptedException {
        Thread.sleep(d);
    }

    private static void sleepQuietly(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
