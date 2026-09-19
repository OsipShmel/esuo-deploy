package honey.road.node.messaging.outbox;

import honey.road.contracts.http.EsuoHeaders;
import honey.road.node.messaging.nats.BranchStreams;
import honey.road.node.messaging.nats.NatsGateway;
import honey.road.node.messaging.nats.NatsProperties;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.PublishOptions;
import io.nats.client.impl.Headers;
import io.nats.client.impl.NatsMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Outbox → {@code BRANCH_EVT} (раздел 10.6).
 * <ul>
 *   <li>Один поток публикации: порядок событий отделения сохраняется.</li>
 *   <li>Синхронная публикация с ожиданием PubAck, {@code Nats-Msg-Id = event_id}: повтор после падения
 *       между PubAck и UPDATE отсекается {@code duplicate_window} стрима.</li>
 *   <li>Ошибка — экспоненциальная задержка (до {@code max-backoff}) и остановка прохода.</li>
 *   <li>Нет связи с NATS — воркер не трогает БД, outbox просто копится.</li>
 * </ul>
 */
@Component
public class OutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

    private final OutboxRepository outbox;
    private final NatsGateway nats;
    private final BranchStreams streams;
    private final NatsProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    OutboxWorker(OutboxRepository outbox, NatsGateway nats, BranchStreams streams, NatsProperties props,
                 TransactionTemplate tx, Clock clock) {
        this.outbox = outbox;
        this.nats = nats;
        this.streams = streams;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${esuo.nats.outbox.poll-interval:200ms}")
    public void tick() {
        var connection = nats.connection();
        if (connection.isEmpty() || !streams.branchEvtReady()) {
            return;
        }
        try {
            // Пока проходы находят полные пачки — продолжаем без паузы (докачка после обрыва).
            int published;
            do {
                published = publishBatch(connection.get());
            } while (published == props.outbox().batchSize());
        } catch (RuntimeException e) {
            log.error("outbox pass failed", e);
        }
    }

    /** @return число опубликованных строк; меньше размера пачки — очередь пуста или остановились на ошибке. */
    int publishBatch(Connection connection) {
        Integer result = tx.execute(status -> {
            var rows = outbox.lockHead(props.outbox().batchSize());
            if (rows.isEmpty()) {
                return 0;
            }
            JetStream js;
            try {
                js = nats.jetStream(connection);
            } catch (java.io.IOException e) {
                return 0;
            }
            int sent = 0;
            for (var row : rows) {
                var now = clock.instant();
                if (row.nextAttemptAt().isAfter(now)) {
                    break;                                   // голова ждёт повтора — ждём и мы
                }
                try {
                    publish(js, row);
                    outbox.markSent(row.id(), now);
                    sent++;
                } catch (Exception e) {
                    var delay = backoff(row.attempts());
                    outbox.markRetry(row.id(), now.plus(delay), e.getClass().getSimpleName() + ": " + e.getMessage());
                    log.warn("outbox publish failed subject={} attempt={} retry_in={}", row.subject(), row.attempts() + 1, delay);
                    break;
                }
            }
            return sent;
        });
        return result == null ? 0 : result;
    }

    private void publish(JetStream js, OutboxRepository.Row row) throws Exception {
        var headers = new Headers();
        row.headers().forEach((k, v) -> {
            if (!EsuoHeaders.NATS_MSG_ID.equals(k)) { headers.add(k, v); }
        });
        var message = NatsMessage.builder()
                .subject(row.subject())
                .headers(headers)
                .data(row.payload().getBytes(StandardCharsets.UTF_8))
                .build();
        String requestId = row.headers().get(EsuoHeaders.REQUEST_ID);
        if (requestId != null) { MDC.put(EsuoHeaders.MDC_REQUEST_ID, requestId); }
        try {
            var ack = js.publish(message, PublishOptions.builder().messageId(row.id().toString()).build());
            if (ack.isDuplicate()) {
                log.info("outbox event already in stream (duplicate) id={}", row.id());
            } else {
                log.debug("outbox published subject={} seq={} stream_seq={}", row.subject(), row.seq(), ack.getSeqno());
            }
        } finally {
            MDC.remove(EsuoHeaders.MDC_REQUEST_ID);
        }
    }

    Duration backoff(int attempts) {
        long millis = 500L << Math.min(attempts, 16);
        return Duration.ofMillis(Math.min(millis, props.outbox().maxBackoff().toMillis()));
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    public void cleanup() {
        int removed = outbox.deleteSentBefore(clock.instant().minus(props.outbox().sentRetention()));
        if (removed > 0) {
            log.info("outbox cleanup removed={}", removed);
        }
    }

    /** Для health и heartbeat. */
    public OutboxStats stats() {
        return outbox.stats(clock.instant());
    }

    public Instant now() { return clock.instant(); }
}
