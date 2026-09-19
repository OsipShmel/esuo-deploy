package honey.road.node.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

/** {@code inbox_processed}: дедупликация входящих сообщений центра (раздел 10.5). */
@Repository
public class InboxRepository {
    private final JdbcTemplate jdbc;

    public InboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Отмечает сообщение обработанным. {@code false} — уже обрабатывалось. Вызывается в той же транзакции,
     * что и применение: откат применения откатывает и отметку, и сообщение будет доставлено снова.
     * Параллельная вставка того же ключа ждёт коммита первой (PRIMARY KEY).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(String messageId, String stream, Instant now) {
        return jdbc.update("""
                INSERT INTO inbox_processed(message_id, stream, processed_at) VALUES (?,?,?)
                ON CONFLICT (message_id) DO NOTHING
                """, messageId, stream, Timestamp.from(now)) == 1;
    }
}
