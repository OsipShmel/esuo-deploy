package honey.road.center.network;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class BranchRegistry {
    private final JdbcClient jdbc;

    public BranchRegistry(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record BranchRow(String id, String name, String timezone, Instant firstSeenAt, Instant lastHeartbeatAt,
                            String lastHeartbeat, boolean sourceAttached) {
    }

    /** @return true, if the branch was encountered for the first time*/
    public boolean upsertHeartbeat(String id, String name, String timezone, String heartbeatJson, Instant now) {
        var inserted = jdbc.sql("""
                        INSERT INTO branches(id,name,timezone,first_seen_at,last_heartbeat_at,last_heartbeat)
                        VALUES (:id,:name,:tz,:now,:now,CAST(:hb AS jsonb))
                        ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, timezone=EXCLUDED.timezone,
                               last_heartbeat_at=EXCLUDED.last_heartbeat_at, last_heartbeat=EXCLUDED.last_heartbeat
                        RETURNING (xmax = 0) AS inserted
                        """)
                .param("id", id).param("name", name).param("tz", timezone).param("hb", heartbeatJson)
                .param("now", Timestamp.from(now))
                .query(Boolean.class).single();
        return Boolean.TRUE.equals(inserted);
    }

    public void markSourceAttached(String id) {
        jdbc.sql("UPDATE branches SET source_attached=true WHERE id=:id").param("id", id).update();
    }

    public List<String> withoutSource() {
        return jdbc.sql("SELECT id FROM branches WHERE NOT source_attached").query(String.class).list();
    }

    public List<String> allIds() {
        return jdbc.sql("SELECT id FROM branches ORDER BY id").query(String.class).list();
    }

    public List<BranchRow> all() {
        return jdbc.sql("SELECT *, last_heartbeat::text AS hb FROM branches ORDER BY id")
                .query((rs, n) -> new BranchRow(rs.getString("id"), rs.getString("name"), rs.getString("timezone"),
                        rs.getTimestamp("first_seen_at").toInstant(), rs.getTimestamp("last_heartbeat_at").toInstant(),
                        rs.getString("hb"), rs.getBoolean("source_attached")))
                .list();
    }

    public boolean exists(String id) {
        return jdbc.sql("SELECT count(*) FROM branches WHERE id=:id").param("id", id).query(Long.class).single() > 0;
    }

    public Optional<BranchRow> get(String id) {
        return all().stream().filter(b -> b.id().equals(id)).findFirst();
    }
}
