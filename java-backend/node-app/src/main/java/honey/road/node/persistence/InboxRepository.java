package honey.road.node.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

/** {@code inbox_processed}: dedup of center messages (sect 10.5). */
@Repository
public class InboxRepository {
    private final JdbcTemplate jdbc;

    public InboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Marks the message processed; {@code false} if it already was. Called in the apply transaction, so a rollback
     * removes the mark and the message is redelivered.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(String messageId, String stream, Instant now) {
        return jdbc.update("""
                INSERT INTO inbox_processed(message_id, stream, processed_at) VALUES (?,?,?)
                ON CONFLICT (message_id) DO NOTHING
                """, messageId, stream, Timestamp.from(now)) == 1;
    }
}
