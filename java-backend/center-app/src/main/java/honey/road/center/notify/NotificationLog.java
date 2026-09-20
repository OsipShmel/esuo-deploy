package honey.road.center.notify;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** {@code notification_log}: every attempt; the unique {@code sent} per {@code dedup_key} prevents double sending. */
@Repository
class NotificationLog {
    private final JdbcClient jdbc;

    NotificationLog(JdbcClient jdbc) { this.jdbc = jdbc; }

    boolean alreadySent(String dedupKey) {
        return jdbc.sql("SELECT count(*) FROM notification_log WHERE dedup_key=:k AND result='sent'")
                .param("k", dedupKey).query(Long.class).single() > 0;
    }

    /** @return false if another attempt already recorded {@code sent} for this key */
    boolean sent(String dedupKey, @Nullable UUID eventId, String branchId, Instant now) {
        try {
            insert(dedupKey, eventId, branchId, "sent", null, now);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    void record(String dedupKey, @Nullable UUID eventId, String branchId, String result, @Nullable String error, Instant now) {
        insert(dedupKey, eventId, branchId, result, error, now);
    }

    Map<String, Long> counts() {
        var counts = new LinkedHashMap<String, Long>();
        for (var r : new String[]{"sent", "failed", "expired", "rejected"}) { counts.put(r, 0L); }
        jdbc.sql("SELECT result, count(*) AS n FROM notification_log GROUP BY result")
                .query((rs, n) -> Map.entry(rs.getString("result"), rs.getLong("n")))
                .list().forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    @Nullable String lastError() {
        return jdbc.sql("SELECT error FROM notification_log WHERE error IS NOT NULL ORDER BY id DESC LIMIT 1")
                .query(String.class).optional().orElse(null);
    }

    private void insert(String dedupKey, @Nullable UUID eventId, String branchId, String result, @Nullable String error, Instant now) {
        jdbc.sql("""
                        INSERT INTO notification_log(dedup_key, event_id, branch_id, result, error, created_at)
                        VALUES (:k, :e, :b, :r, :err, :now)
                        """)
                .param("k", dedupKey).param("e", eventId).param("b", branchId).param("r", result).param("err", error)
                .param("now", Timestamp.from(now)).update();
    }
}
