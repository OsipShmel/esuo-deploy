package honey.road.center.ingest;

import honey.road.center.nats.CenterNats;
import honey.road.center.nats.CenterStreams;
import honey.road.contracts.event.Subjects;
import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.MessageConsumer;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code analytics-ingest} (раздел 10.5): durable pull-потребитель {@code CENTER_EVT} → таблица {@code events}.
 * Доставка «хотя бы один раз», дедупликация {@code ON CONFLICT (event_id) DO NOTHING}.
 * После рестарта центра чтение продолжается с позиции durable-потребителя.
 */
@Component
public class EventIngest {
    private static final Logger log = LoggerFactory.getLogger(EventIngest.class);
    static final String CONSUMER = "analytics-ingest";

    private final CenterNats nats;
    private final CenterStreams streams;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private volatile MessageConsumer consumer;

    public EventIngest(CenterNats nats, CenterStreams streams, JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.nats = nats; this.streams = streams; this.jdbc = jdbc; this.json = json; this.clock = clock;
    }

    @PostConstruct
    void register() {
        nats.onConnected(this::start);
    }

    synchronized void start(Connection c) {
        if (consumer != null || !streams.ready()) { return; }       // MessageConsumer сам переживает реконнекты
        try {
            c.jetStreamManagement().addOrUpdateConsumer(Subjects.STREAM_CENTER_EVT, ConsumerConfiguration.builder()
                    .durable(CONSUMER)
                    .ackPolicy(AckPolicy.Explicit)
                    .deliverPolicy(DeliverPolicy.All)
                    .ackWait(Duration.ofSeconds(30))
                    .maxDeliver(-1)
                    .build());
            var context = c.getConsumerContext(Subjects.STREAM_CENTER_EVT, CONSUMER);
            consumer = context.consume(this::handle);
            log.info("ingest consuming {} as {}", Subjects.STREAM_CENTER_EVT, CONSUMER);
        } catch (Exception e) {
            log.error("cannot start ingest consumer", e);
        }
    }

    void handle(Message msg) {
        try {
            var body = new String(msg.getData(), StandardCharsets.UTF_8);
            var e = json.readTree(body);
            var eventId = e.path("event_id").asString(null);
            if (eventId == null) {
                log.warn("event without event_id on {}, skipped", msg.getSubject());
                msg.ack();
                return;
            }
            var aggregateId = e.path("aggregate_id").asString(null);
            jdbc.sql("""
                            INSERT INTO events(event_id,branch_id,event_type,aggregate_type,aggregate_id,occurred_at,received_at,payload)
                            VALUES (:id,:b,:type,:at,:aid,:occurred,:now,CAST(:payload AS jsonb))
                            ON CONFLICT (event_id) DO NOTHING
                            """)
                    .param("id", UUID.fromString(eventId))
                    .param("b", e.path("branch_id").asString(Subjects.branchIdOf(msg.getSubject())))
                    .param("type", e.path("event_type").asString("unknown"))
                    .param("at", e.path("aggregate_type").asString(null))
                    .param("aid", aggregateId == null ? null : UUID.fromString(aggregateId))
                    .param("occurred", Timestamp.from(Instant.parse(e.path("occurred_at").asString(clock.instant().toString()))))
                    .param("now", Timestamp.from(clock.instant()))
                    .param("payload", body)
                    .update();
            msg.ack();
        } catch (RuntimeException ex) {
            log.warn("ingest failed, will be redelivered: {}", ex.toString());
            msg.nakWithDelay(Duration.ofSeconds(5));
        }
    }

    /** Если при подключении стримы ещё не были готовы — дозапуск. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "PT10S", initialDelayString = "PT10S")
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
