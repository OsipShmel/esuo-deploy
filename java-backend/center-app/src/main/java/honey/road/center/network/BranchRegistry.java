package honey.road.center.network;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class BranchRegistry {
    private final JdbcClient jdbc;

    public BranchRegistry(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record BranchRow(String id, String name, String timezone, Instant firstSeenAt, Instant lastHeartbeatAt,
                            String lastHeartbeat, boolean sourceAttached, @Nullable Integer configPublishedVersion,
                            @Nullable Instant configPublishedAt, @Nullable Integer configAppliedVersion) {
    }

    static final int BATCH = 500;

    /**
     * Writes a batch of heartbeats (C3b): one multi-row upsert per {@value #BATCH} branches instead of one statement
     * per heartbeat. {@code last_heartbeat_at} is when the heartbeat arrived, not when it was flushed.
     *
     * @return ids seen for the first time
     */
    List<String> upsertHeartbeats(List<HeartbeatRow> rows) {
        var inserted = new ArrayList<String>();
        for (int from = 0; from < rows.size(); from += BATCH) {
            var chunk = rows.subList(from, Math.min(rows.size(), from + BATCH));
            var sql = new StringBuilder("INSERT INTO branches(id,name,timezone,first_seen_at,last_heartbeat_at,last_heartbeat) VALUES ");
            var params = new ArrayList<Object>(chunk.size() * 6);
            for (int i = 0; i < chunk.size(); i++) {
                var r = chunk.get(i);
                sql.append(i == 0 ? "" : ",").append("(?,?,?,?,?,CAST(? AS jsonb))");
                var at = Timestamp.from(r.receivedAt());
                params.add(r.branchId()); params.add(r.name()); params.add(r.timezone()); params.add(at); params.add(at);
                params.add(r.json());
            }
            sql.append(" ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, timezone=EXCLUDED.timezone,")
                    .append(" last_heartbeat_at=EXCLUDED.last_heartbeat_at, last_heartbeat=EXCLUDED.last_heartbeat")
                    .append(" RETURNING id, (xmax = 0) AS inserted");
            jdbc.sql(sql.toString()).params(params)
                    .query((rs, n) -> rs.getBoolean("inserted") ? rs.getString("id") : null)
                    .list().stream().filter(Objects::nonNull).forEach(inserted::add);
        }
        return inserted;
    }

    /** {@code branch_metrics} rows for the given heartbeats, in batches. */
    void insertMetrics(List<HeartbeatRow> rows) {
        for (int from = 0; from < rows.size(); from += BATCH) {
            var chunk = rows.subList(from, Math.min(rows.size(), from + BATCH));
            var sql = new StringBuilder("INSERT INTO branch_metrics(branch_id,ts,sys,queue) VALUES ");
            var params = new ArrayList<Object>(chunk.size() * 4);
            for (int i = 0; i < chunk.size(); i++) {
                var r = chunk.get(i);
                sql.append(i == 0 ? "" : ",").append("(?,?,CAST(? AS jsonb),CAST(? AS jsonb))");
                params.add(r.branchId()); params.add(Timestamp.from(r.receivedAt())); params.add(r.sys()); params.add(r.queue());
            }
            jdbc.sql(sql.toString()).params(params).update();
        }
    }

    int deleteMetricsBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM branch_metrics WHERE ts < :cutoff").param("cutoff", Timestamp.from(cutoff)).update();
    }

    public record MetricsRow(Instant ts, @Nullable String sys, String queue) {
    }

    public List<MetricsRow> metrics(String branchId, Instant from, Instant to, int limit) {
        return jdbc.sql("SELECT ts, sys::text AS sys, queue::text AS queue FROM branch_metrics"
                        + " WHERE branch_id = :b AND ts >= :from AND ts < :to ORDER BY ts LIMIT :limit")
                .param("b", branchId).param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).param("limit", limit)
                .query((rs, n) -> new MetricsRow(rs.getTimestamp("ts").toInstant(), rs.getString("sys"), rs.getString("queue")))
                .list();
    }

    void markSourceAttached(List<String> ids) {
        if (ids.isEmpty()) { return; }
        jdbc.sql("UPDATE branches SET source_attached=true WHERE id = ANY(:ids)")
                .param("ids", ids.toArray(String[]::new)).update();
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
