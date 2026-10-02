package honey.road.center.network;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class BranchRegistry {
    private final JdbcClient jdbc;
    private final JdbcTemplate batch;

    public BranchRegistry(JdbcClient jdbc, JdbcTemplate batch) { this.jdbc = jdbc; this.batch = batch; }

    public record BranchRow(String id, String name, String timezone, Instant firstSeenAt, Instant lastHeartbeatAt,
                            String lastHeartbeat, boolean sourceAttached, @Nullable Integer configPublishedVersion,
                            @Nullable Instant configPublishedAt, @Nullable Integer configAppliedVersion) {
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

    /**
     * Buffered heartbeats of known branches in one batch (design 6.2); joins the caller's transaction. An older
     * heartbeat never overwrites a newer one (a batch put back after a failed write).
     */
    public void saveHeartbeats(List<Heartbeat> heartbeats) {
        batch.batchUpdate("""
                        INSERT INTO branches(id,name,timezone,first_seen_at,last_heartbeat_at,last_heartbeat)
                        VALUES (?,?,?,?,?,CAST(? AS jsonb))
                        ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, timezone=EXCLUDED.timezone,
                               last_heartbeat_at=EXCLUDED.last_heartbeat_at, last_heartbeat=EXCLUDED.last_heartbeat
                        WHERE branches.last_heartbeat_at <= EXCLUDED.last_heartbeat_at""",
                heartbeats.stream().map(h -> new Object[]{h.branchId(), h.name(), h.timezone(),
                        Timestamp.from(h.receivedAt()), Timestamp.from(h.receivedAt()), h.json()}).toList());
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
                .query((rs, n) -> {
                    var publishedAt = rs.getTimestamp("config_published_at");
                    return new BranchRow(rs.getString("id"), rs.getString("name"), rs.getString("timezone"),
                            rs.getTimestamp("first_seen_at").toInstant(), rs.getTimestamp("last_heartbeat_at").toInstant(),
                            rs.getString("hb"), rs.getBoolean("source_attached"), (Integer) rs.getObject("config_published_version"),
                            publishedAt == null ? null : publishedAt.toInstant(), (Integer) rs.getObject("config_applied_version"));
                })
                .list();
    }

    public boolean exists(String id) {
        return jdbc.sql("SELECT count(*) FROM branches WHERE id=:id").param("id", id).query(Long.class).single() > 0;
    }

    public Optional<BranchRow> get(String id) {
        return all().stream().filter(b -> b.id().equals(id)).findFirst();
    }
}
