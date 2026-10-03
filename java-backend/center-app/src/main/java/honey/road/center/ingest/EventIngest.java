package honey.road.center.ingest;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Counter;
import honey.road.center.nats.CenterNats;
import honey.road.center.nats.CenterStreams;
import honey.road.contracts.event.Subjects;
import io.nats.client.Connection;
import io.nats.client.ConsumerContext;
import io.nats.client.FetchConsumeOptions;
import io.nats.client.Message;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code analytics-ingest} (docs, section 10.5): durable pull consumer {@code CENTER_EVT} → {@code events} table.
 * "At-least-once", deduplication via {@code ON CONFLICT (event_id) DO NOTHING}.
 * After a center restart, reading resumes from the durable consumer's position.
 *
 * <p>Batched (final plan, C3a): up to {@value #BATCH} messages or {@value #WAIT_MS} ms per fetch (a full batch returns at
 * once; jnats does not allow waiting less than a second), one multi-row insert,
 * acks after it succeeded; a database error naks the whole batch for redelivery. One insert per event used to cap
 * the center at about a thousand events a second, well under what a swarm of branches delivers.
 */
@Component
public class EventIngest {
    private static final Logger log = LoggerFactory.getLogger(EventIngest.class);
    static final String CONSUMER = "analytics-ingest";
    static final int BATCH = 500;
    static final int WAIT_MS = 1000;

    private final CenterNats nats;
    private final CenterStreams streams;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private volatile Thread worker;

    private final Counter ingested;
    private final Counter failed;
    private final DistributionSummary batchSize;

    public EventIngest(CenterNats nats, CenterStreams streams, JdbcClient jdbc, JsonMapper json, Clock clock,
                       MeterRegistry meters) {
        this.nats = nats; this.streams = streams; this.jdbc = jdbc; this.json = json; this.clock = clock;
        this.ingested = Counter.builder("esuo.events.ingested").description("Branch events written to the database")
                .register(meters);
        this.failed = Counter.builder("esuo.events.failed").description("Events of failed batches, redelivered")
                .register(meters);
        this.batchSize = DistributionSummary.builder("esuo.events.batch").description("Events per insert")
                .register(meters);
    }

    @PostConstruct
    void register() {
        nats.onConnected(this::start);
    }

    synchronized void start(Connection c) {
        if (running() || !streams.ready()) { return; }
        try {
            c.jetStreamManagement().addOrUpdateConsumer(Subjects.STREAM_CENTER_EVT, ConsumerConfiguration.builder()
                    .durable(CONSUMER)
                    .ackPolicy(AckPolicy.Explicit)
                    .deliverPolicy(DeliverPolicy.All)
                    .ackWait(Duration.ofSeconds(30))
                    .maxDeliver(-1)
                    .maxAckPending(BATCH * 20)
                    .build());
            var context = c.getConsumerContext(Subjects.STREAM_CENTER_EVT, CONSUMER);
            worker = Thread.ofPlatform().name("analytics-ingest").daemon().start(() -> loop(context));
            log.info("ingest consuming {} as {} in batches of {}", Subjects.STREAM_CENTER_EVT, CONSUMER, BATCH);
        } catch (Exception e) {
            log.error("cannot start ingest consumer", e);
        }
    }

    static FetchConsumeOptions fetchOptions() {
        return FetchConsumeOptions.builder().maxMessages(BATCH).expiresIn(WAIT_MS).build();
    }

    private void loop(ConsumerContext context) {
        try {
            run(context, fetchOptions());
        } catch (RuntimeException | Error e) {
            log.error("ingest worker stopped, restarting within 10 s", e);   // never die silently again
        }
    }

    private void run(ConsumerContext context, FetchConsumeOptions options) {
        while (!Thread.currentThread().isInterrupted()) {
            var batch = new ArrayList<Message>(BATCH);
            try (var fetch = context.fetch(options)) {
                for (Message m; (m = fetch.nextMessage()) != null; ) {
                    batch.add(m);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // NATS reconnecting or the consumer recreated: try again shortly, the durable keeps its position.
                log.debug("ingest fetch failed: {}", e.toString());
                pause();
                continue;
            }
            if (!batch.isEmpty()) {
                write(batch);
            }
        }
    }

    /** Parses, inserts the good rows in one statement, then acks; malformed ones are terminated, not redelivered. */
    void write(List<Message> batch) {
        var now = clock.instant();
        var rows = new ArrayList<EventRow>(batch.size());
        var stored = new ArrayList<Message>(batch.size());
        for (var msg : batch) {
            try {
                rows.add(EventRow.parse(json, msg.getSubject(), msg.getData(), now));
                stored.add(msg);
            } catch (EventRow.Malformed e) {
                log.warn("{}, skipped", e.getMessage());
                msg.term();
            }
        }
        if (rows.isEmpty()) { return; }
        try {
            insert(rows, now);
        } catch (RuntimeException e) {
            failed.increment(rows.size());
            log.warn("ingest batch of {} failed, will be redelivered: {}", rows.size(), e.toString());
            stored.forEach(m -> m.nakWithDelay(Duration.ofSeconds(5)));
            return;
        }
        stored.forEach(Message::ack);
        ingested.increment(rows.size());
        batchSize.record(rows.size());
    }

    private void insert(List<EventRow> rows, java.time.Instant receivedAt) {
        var sql = new StringBuilder(
                "INSERT INTO events(event_id,branch_id,event_type,aggregate_type,aggregate_id,occurred_at,received_at,payload) VALUES ");
        var params = new ArrayList<Object>(rows.size() * 8);
        var received = Timestamp.from(receivedAt);
        for (int i = 0; i < rows.size(); i++) {
            var r = rows.get(i);
            sql.append(i == 0 ? "" : ",").append("(?,?,?,?,?,?,?,CAST(? AS jsonb))");
            params.add(r.eventId()); params.add(r.branchId()); params.add(r.eventType()); params.add(r.aggregateType());
            params.add(r.aggregateId()); params.add(Timestamp.from(r.occurredAt())); params.add(received); params.add(r.payload());
        }
        sql.append(" ON CONFLICT (event_id) DO NOTHING");
        jdbc.sql(sql.toString()).params(params).update();
    }

    private static void pause() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Scheduled(fixedDelayString = "PT10S", initialDelayString = "PT10S")
    public void ensureStarted() {
        if (!running()) {
            nats.connection().ifPresent(this::start);
        }
    }

    public boolean running() {
        var w = worker;
        return w != null && w.isAlive();
    }

    @PreDestroy
    void stop() {
        var w = worker;
        if (w != null) { w.interrupt(); }
    }
}
