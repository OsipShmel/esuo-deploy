package honey.road.center.network;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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

    static final int BATCH = 500;

    /** Which of these branches the registry already has (one query for an onboarding batch). */
    List<String> existing(List<String> ids) {
        if (ids.isEmpty()) { return List.of(); }
        return jdbc.sql("SELECT id FROM branches WHERE id = ANY(:ids)")
                .param("ids", ids.toArray(String[]::new)).query(String.class).list();
    }

    /**
     * Registers new branches from their first heartbeats: one multi-row insert per {@value #BATCH} instead of one
     * statement per branch. A branch inserted meanwhile by someone else is left as it is.
     *
     * @return ids inserted by this call
     */
    List<String> register(List<Heartbeat> first) {
        var inserted = new ArrayList<String>();
        for (int from = 0; from < first.size(); from += BATCH) {
            var chunk = first.subList(from, Math.min(first.size(), from + BATCH));
            var sql = new StringBuilder("INSERT INTO branches(id,name,timezone,first_seen_at,last_heartbeat_at,last_heartbeat) VALUES ");
            var params = new ArrayList<Object>(chunk.size() * 6);
            for (int i = 0; i < chunk.size(); i++) {
                var h = chunk.get(i);
                sql.append(i == 0 ? "" : ",").append("(?,?,?,?,?,CAST(? AS jsonb))");
                var at = Timestamp.from(h.receivedAt());
                params.add(h.branchId()); params.add(h.name()); params.add(h.timezone()); params.add(at); params.add(at);
                params.add(h.json());
            }
            sql.append(" ON CONFLICT (id) DO NOTHING RETURNING id");
            inserted.addAll(jdbc.sql(sql.toString()).params(params).query(String.class).list());
        }
        return inserted;
    }

    void markSourceAttached(List<String> ids) {
        if (ids.isEmpty()) { return; }
        jdbc.sql("UPDATE branches SET source_attached=true WHERE id = ANY(:ids)")
                .param("ids", ids.toArray(String[]::new)).update();
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

    /**
     * A branch whose events are not yet sourced into {@code CENTER_EVT}, and where they live: its own leaf
     * ({@code eventsVia} null) or a regional domain from its last heartbeat.
     */
    public record Unattached(String id, @Nullable String eventsVia) {
    }

    public List<Unattached> withoutSource() {
        return jdbc.sql("SELECT id, last_heartbeat->>'events_via' AS via FROM branches WHERE NOT source_attached")
                .query((rs, n) -> new Unattached(rs.getString("id"), EventsVia.of(rs.getString("via"))))
                .list();
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
