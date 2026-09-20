package honey.road.center.dlq;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Center DLQ {@code dead_letters} (sect 8.4): notifications and MP callbacks that ran out of attempts or were refused. */
@Repository
public class DeadLetters {
    public static final String NOTIFICATION = "notification";
    public static final String MP_CALLBACK = "mp_callback";

    private final JdbcClient jdbc;

    public DeadLetters(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record DeadLetter(long id, String kind, String key, String branchId, String payload, int attempts, String reason,
                             @Nullable String lastError, Instant createdAt, @Nullable Instant resolvedAt) {
    }

    /** One open entry per key and kind: a repeated failure of the same message updates it. */
    public void put(String kind, String key, String branchId, String payload, int attempts, String reason,
                    @Nullable String lastError, Instant now) {
        int updated = jdbc.sql("""
                        UPDATE dead_letters SET attempts=attempts+:n, reason=:r, last_error=:e, payload=CAST(:p AS jsonb)
                         WHERE kind=:k AND key=:key AND resolved_at IS NULL
                        """)
                .param("n", attempts).param("r", reason).param("e", lastError).param("p", payload)
                .param("k", kind).param("key", key).update();
        if (updated == 0) {
            jdbc.sql("""
                            INSERT INTO dead_letters(kind, key, branch_id, payload, attempts, reason, last_error, created_at)
                            VALUES (:k, :key, :b, CAST(:p AS jsonb), :n, :r, :e, :now)
                            """)
                    .param("k", kind).param("key", key).param("b", branchId).param("p", payload).param("n", attempts)
                    .param("r", reason).param("e", lastError).param("now", Timestamp.from(now)).update();
        }
    }

    public List<DeadLetter> list(@Nullable String kind, boolean resolved, int limit) {
        return jdbc.sql("""
                        SELECT *, payload::text AS p FROM dead_letters
                         WHERE (CAST(:k AS text) IS NULL OR kind = :k) AND (resolved_at IS NOT NULL) = :resolved
                         ORDER BY created_at DESC LIMIT :limit
                        """)
                .param("k", kind).param("resolved", resolved).param("limit", limit)
                .query(DeadLetters::row).list();
    }

    public Optional<DeadLetter> get(long id) {
        return jdbc.sql("SELECT *, payload::text AS p FROM dead_letters WHERE id=:id").param("id", id)
                .query(DeadLetters::row).optional();
    }

    public long countOpen(String kind) {
        return jdbc.sql("SELECT count(*) FROM dead_letters WHERE kind=:k AND resolved_at IS NULL")
                .param("k", kind).query(Long.class).single();
    }

    public void resolve(long id, Instant now) {
        jdbc.sql("UPDATE dead_letters SET resolved_at=:now WHERE id=:id AND resolved_at IS NULL")
                .param("now", Timestamp.from(now)).param("id", id).update();
    }

    /** Closes the open entry of a key that was delivered by other means (e.g. a later redelivery). */
    public void resolveKey(String kind, String key, Instant now) {
        jdbc.sql("UPDATE dead_letters SET resolved_at=:now WHERE kind=:k AND key=:key AND resolved_at IS NULL")
                .param("now", Timestamp.from(now)).param("k", kind).param("key", key).update();
    }

    public void failedRetry(long id, String error) {
        jdbc.sql("UPDATE dead_letters SET attempts=attempts+1, last_error=:e WHERE id=:id")
                .param("e", error).param("id", id).update();
    }

    private static DeadLetter row(ResultSet rs, int n) throws SQLException {
        var resolved = rs.getTimestamp("resolved_at");
        return new DeadLetter(rs.getLong("id"), rs.getString("kind"), rs.getString("key"), rs.getString("branch_id"),
                rs.getString("p"), rs.getInt("attempts"), rs.getString("reason"), rs.getString("last_error"),
                rs.getTimestamp("created_at").toInstant(), resolved == null ? null : resolved.toInstant());
    }
}
