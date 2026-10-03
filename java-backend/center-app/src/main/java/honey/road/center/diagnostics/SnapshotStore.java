package honey.road.center.diagnostics;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** {@code branch_diagnostics} (design 7). */
@Repository
class SnapshotStore {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    SnapshotStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc; this.json = json;
    }

    void save(UUID id, String branchId, Instant takenAt, String requestedBy, String payload, int sizeBytes) {
        jdbc.sql("""
                        INSERT INTO branch_diagnostics(id, branch_id, taken_at, requested_by, size_bytes, payload)
                        VALUES (:id, :b, :t, :by, :size, CAST(:p AS jsonb))""")
                .param("id", id).param("b", branchId).param("t", Timestamp.from(takenAt)).param("by", requestedBy)
                .param("size", sizeBytes).param("p", payload).update();
    }

    /** Newest first, without the payload. */
    List<Map<String, Object>> list(String branchId, int limit) {
        return jdbc.sql("""
                        SELECT id, branch_id, taken_at, requested_by, size_bytes FROM branch_diagnostics
                        WHERE branch_id = :b ORDER BY taken_at DESC LIMIT :n""")
                .param("b", branchId).param("n", limit)
                .query((rs, n) -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("id", rs.getObject("id", UUID.class));
                    m.put("branch_id", rs.getString("branch_id"));
                    m.put("taken_at", rs.getTimestamp("taken_at").toInstant());
                    m.put("requested_by", rs.getString("requested_by"));
                    m.put("size_bytes", rs.getInt("size_bytes"));
                    return (Map<String, Object>) m;
                }).list();
    }

    Optional<Map<String, Object>> get(UUID id) {
        return jdbc.sql("""
                        SELECT id, branch_id, taken_at, requested_by, size_bytes, payload::text AS payload
                        FROM branch_diagnostics WHERE id = :id""")
                .param("id", id)
                .query((rs, n) -> view(rs.getObject("id", UUID.class), rs.getString("branch_id"),
                        rs.getTimestamp("taken_at").toInstant(), rs.getString("requested_by"), rs.getInt("size_bytes"),
                        json.readTree(rs.getString("payload"))))
                .optional();
    }

    int deleteOlderThan(Instant cut) {
        return jdbc.sql("DELETE FROM branch_diagnostics WHERE taken_at < :cut").param("cut", Timestamp.from(cut)).update();
    }

    static Map<String, Object> view(UUID id, String branchId, Instant takenAt, String requestedBy, int size, JsonNode payload) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("branch_id", branchId);
        m.put("taken_at", takenAt);
        m.put("requested_by", requestedBy);
        m.put("size_bytes", size);
        m.put("payload", payload);
        return m;
    }
}
