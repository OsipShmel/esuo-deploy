package honey.road.node.persistence;

import honey.road.contracts.dto.TicketView;
import honey.road.node.queue.domain.DomainException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import honey.road.node.config.JsonCodec;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class IdempotencyRepository {
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    public IdempotencyRepository(JdbcTemplate jdbc, JsonCodec json) { this.jdbc = jdbc; this.json = json; }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<TicketView> reserve(String scope, String key, String hash, Instant now) {
        // Конкурентный INSERT ждёт завершения первой транзакции с тем же ключом.
        jdbc.update("""
                INSERT INTO idempotency_keys(scope,key,request_hash,created_at)
                VALUES (?,?,?,?) ON CONFLICT (scope,key) DO NOTHING
                """, scope, key, hash, Timestamp.from(now));
        return jdbc.query("SELECT request_hash,response_body::text FROM idempotency_keys WHERE scope=? AND key=? FOR UPDATE", (rs, n) -> {
            if (!hash.equals(rs.getString("request_hash"))) {
                throw new DomainException("IDEMPOTENCY_CONFLICT", "Ключ уже использован для другого запроса");
            }
            String body = rs.getString("response_body");
            return body == null ? Optional.<TicketView>empty() : Optional.of(json.readValue(body, TicketView.class));
        }, scope, key).getFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(String scope, String key, TicketView view) {
        int updated = jdbc.update("""
                UPDATE idempotency_keys SET response_code=201,response_body=CAST(? AS jsonb)
                WHERE scope=? AND key=? AND response_body IS NULL
                """, json.writeValueAsString(view), scope, key);
        if (updated != 1) { throw new IllegalStateException("Ответ идемпотентной операции уже сохранён"); }
    }
}
