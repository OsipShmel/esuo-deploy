package honey.road.node.messaging.outbox;

import honey.road.node.config.JsonCodec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.Duration;

@Repository
class OutboxRepository {
    private final JdbcClient jdbc;
    private final JsonCodec json;

    OutboxRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    record Row(UUID id, long seq, String subject, String payload, Map<String, String> headers, int attempts,
               Instant nextAttemptAt) {
    }

    /**
     * Head of the publish queue in {@code seq} order, locked. Deferred rows are not skipped:
     * if the head waits for a retry, the worker waits too, otherwise event order breaks.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @SuppressWarnings("unchecked")
    List<Row> lockHead(int limit) {
        return jdbc.sql("""
                        SELECT id, seq, subject, payload::text AS payload, headers::text AS headers, attempts, next_attempt_at
                          FROM outbox WHERE status = 'pending'
                         ORDER BY seq LIMIT :limit
                           FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", limit)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getLong("seq"), rs.getString("subject"),
                        rs.getString("payload"), (Map<String, String>) json.readValue(rs.getString("headers"), Map.class),
                        rs.getInt("attempts"), rs.getTimestamp("next_attempt_at").toInstant()))
                .list();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void markSent(UUID id, Instant now) {
        jdbc.sql("UPDATE outbox SET status='sent', sent_at=:now, attempts=attempts+1, last_error=NULL WHERE id=:id")
                .param("now", Timestamp.from(now)).param("id", id).update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void markRetry(UUID id, Instant next, String error) {
        jdbc.sql("UPDATE outbox SET attempts=attempts+1, next_attempt_at=:next, last_error=:error WHERE id=:id")
                .param("next", Timestamp.from(next)).param("error", truncate(error)).param("id", id).update();
    }

    int deleteSentBefore(Instant threshold) {
        return jdbc.sql("DELETE FROM outbox WHERE status='sent' AND sent_at < :t")
                .param("t", Timestamp.from(threshold)).update();
    }

    /** For health: pending count and age of the oldest. */
    OutboxStats stats(Instant now) {
        return jdbc.sql("SELECT count(*) AS n, min(created_at) AS oldest FROM outbox WHERE status='pending'")
                .query((rs, i) -> {
                    var oldest = rs.getTimestamp("oldest");
                    return new OutboxStats(rs.getLong("n"), Optional.ofNullable(oldest)
                            .map(t -> Duration.between(t.toInstant(), now)).orElse(Duration.ZERO));
                }).single();
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 500 ? s.substring(0, 500) : s;
    }
}
