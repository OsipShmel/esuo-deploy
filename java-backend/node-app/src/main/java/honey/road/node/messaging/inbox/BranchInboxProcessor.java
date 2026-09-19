package honey.road.node.messaging.inbox;

import honey.road.contracts.event.Subjects;
import honey.road.contracts.inbox.ConfigMessage;
import honey.road.contracts.inbox.MpAppointmentMessage;
import honey.road.node.config.JsonCodec;
import honey.road.node.config.NodeProperties;
import honey.road.node.persistence.InboxRepository;
import honey.road.node.queue.application.BranchConfigService;
import honey.road.node.queue.application.MpAppointmentHandler;
import honey.road.node.queue.domain.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

/**
 * Обработка одного сообщения {@code BRANCH_INBOX} без зависимости от NATS (границы A/B: транспорт — в
 * {@link BranchInboxConsumer}, здесь только смысл сообщения).
 * <ul>
 *   <li>Дедупликация по {@code inbox_processed} в той же транзакции, что и применение (10.5).</li>
 *   <li>Временная ошибка (БД недоступна) — исключение наружу: сообщение не подтверждается и придёт снова.</li>
 *   <li>Постоянная ошибка (битый JSON, невалидная конфигурация) — сообщение отмечается обработанным и
 *       результат {@link Outcome#REJECTED}: бесконечные повторы бессмысленны.</li>
 *   <li>Отказ по правилам записи МП — не ошибка обработки: результат {@code mp.appointment.rejected} уходит в центр.</li>
 * </ul>
 */
@Service
public class BranchInboxProcessor {
    private static final Logger log = LoggerFactory.getLogger(BranchInboxProcessor.class);

    public enum Outcome { APPLIED, DUPLICATE, STALE, REJECTED }

    private final BranchConfigService config;
    private final MpAppointmentHandler mp;
    private final InboxRepository inbox;
    private final JsonCodec json;
    private final NodeProperties node;
    private final TransactionTemplate tx;
    private final Clock clock;

    public BranchInboxProcessor(BranchConfigService config, MpAppointmentHandler mp, InboxRepository inbox, JsonCodec json,
                                NodeProperties node, TransactionTemplate tx, Clock clock) {
        this.config = config; this.mp = mp; this.inbox = inbox; this.json = json; this.node = node;
        this.tx = tx; this.clock = clock;
    }

    public Outcome process(String subject, String messageId, String body) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("message id is required for deduplication");
        }
        try {
            if (Subjects.CONFIG_GLOBAL.equals(subject) || Subjects.configBranch(node.branchId()).equals(subject)) {
                return handleConfig(messageId, body);
            }
            if (Subjects.mpInbox(node.branchId()).equals(subject)) {
                return handleMp(messageId, body);
            }
            log.warn("inbox: unknown subject {} id={}", subject, messageId);
            return discard(messageId);
        } catch (DomainException | IllegalStateException e) {
            // IllegalStateException — битый JSON/невалидные данные из JsonCodec.
            log.warn("inbox: message rejected id={} subject={}: {}", messageId, subject, e.getMessage());
            return discard(messageId);
        }
    }

    private Outcome handleConfig(String messageId, String body) {
        var message = json.readValue(body, ConfigMessage.class);
        return tx.execute(s -> {
            if (!inbox.markProcessed(messageId, Subjects.STREAM_BRANCH_INBOX, now())) {
                return Outcome.DUPLICATE;
            }
            boolean applied = config.applyCentral(message);
            return applied ? Outcome.APPLIED : Outcome.STALE;
        });
    }

    private Outcome handleMp(String messageId, String body) {
        var message = json.readValue(body, MpAppointmentMessage.class);
        try {
            return tx.execute(s -> {
                if (!inbox.markProcessed(messageId, Subjects.STREAM_BRANCH_INBOX, now())) {
                    return Outcome.DUPLICATE;
                }
                mp.confirm(message);
                return Outcome.APPLIED;
            });
        } catch (DomainException e) {
            // Откат снял и отметку «обработано»: фиксируем отказ и отметку одной новой транзакцией.
            return tx.execute(s -> {
                if (!inbox.markProcessed(messageId, Subjects.STREAM_BRANCH_INBOX, now())) {
                    return Outcome.DUPLICATE;
                }
                mp.reject(message, e.code(), e.getMessage());
                return Outcome.REJECTED;
            });
        }
    }

    private Outcome discard(String messageId) {
        tx.executeWithoutResult(s -> inbox.markProcessed(messageId, Subjects.STREAM_BRANCH_INBOX, now()));
        return Outcome.REJECTED;
    }

    private java.time.Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
