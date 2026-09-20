package honey.road.center.mp;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** {@code mp_requests}: MP webhooks by {@code mp_appointment_id} and the fate of their callbacks (sect 8.4). */
@Repository
class MpRequests {
    private final JdbcClient jdbc;

    MpRequests(JdbcClient jdbc) { this.jdbc = jdbc; }

    record MpRequest(String mpAppointmentId, String branchId, String bodyHash, String body, String status, boolean published,
                     @Nullable String displayNumber, @Nullable String reason, int callbackAttempts,
                     Instant createdAt, Instant updatedAt) {
    }

    /** @return the existing row if the id is already known, empty if a new row was inserted */
    Optional<MpRequest> insertIfAbsent(String id, String branchId, String bodyHash, String body, Instant now) {
        int inserted = jdbc.sql("""
                        INSERT INTO mp_requests(mp_appointment_id, branch_id, body_hash, body, status, created_at, updated_at)
                        VALUES (:id, :b, :h, CAST(:body AS jsonb), 'accepted', :now, :now)
                        ON CONFLICT (mp_appointment_id) DO NOTHING
                        """)
                .param("id", id).param("b", branchId).param("h", bodyHash).param("body", body)
                .param("now", Timestamp.from(now)).update();
        return inserted == 1 ? Optional.empty() : get(id);
    }

    Optional<MpRequest> get(String id) {
        return jdbc.sql("SELECT *, body::text AS b FROM mp_requests WHERE mp_appointment_id=:id").param("id", id)
                .query(MpRequests::row).optional();
    }

    List<MpRequest> recent(int limit) {
        return jdbc.sql("SELECT *, body::text AS b FROM mp_requests ORDER BY created_at DESC LIMIT :limit")
                .param("limit", limit).query(MpRequests::row).list();
    }

    void markPublished(String id, Instant now) {
        jdbc.sql("UPDATE mp_requests SET published=true, updated_at=:now WHERE mp_appointment_id=:id")
                .param("now", Timestamp.from(now)).param("id", id).update();
    }

    /** Branch result; does not overwrite a finished callback. */
    void result(String id, String status, @Nullable String displayNumber, @Nullable String reason, Instant now) {
        jdbc.sql("""
                        UPDATE mp_requests SET status=:s, display_number=COALESCE(:n, display_number), reason=:r, updated_at=:now
                         WHERE mp_appointment_id=:id AND status IN ('accepted', 'confirmed', 'rejected')
                        """)
                .param("s", status).param("n", displayNumber).param("r", reason).param("now", Timestamp.from(now))
                .param("id", id).update();
    }

    void callback(String id, String status, Instant now) {
        jdbc.sql("""
                        UPDATE mp_requests SET status=:s, callback_attempts=callback_attempts+1, updated_at=:now
                         WHERE mp_appointment_id=:id
                        """)
                .param("s", status).param("now", Timestamp.from(now)).param("id", id).update();
    }

    void failedAttempt(String id, Instant now) {
        jdbc.sql("UPDATE mp_requests SET callback_attempts=callback_attempts+1, updated_at=:now WHERE mp_appointment_id=:id")
                .param("now", Timestamp.from(now)).param("id", id).update();
    }

    private static MpRequest row(ResultSet rs, int n) throws SQLException {
        return new MpRequest(rs.getString("mp_appointment_id"), rs.getString("branch_id"), rs.getString("body_hash"),
                rs.getString("b"), rs.getString("status"), rs.getBoolean("published"), rs.getString("display_number"),
                rs.getString("reason"), rs.getInt("callback_attempts"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
