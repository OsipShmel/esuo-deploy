package honey.road.center.network;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@Repository
public class BranchRegistry {
    private final JdbcClient jdbc;
    private final JdbcTemplate batch;

    private final JdbcTemplate cursor;
    private final TransactionTemplate readOnly;

    public BranchRegistry(JdbcClient jdbc, JdbcTemplate batch, PlatformTransactionManager transactions) {
        this.jdbc = jdbc; this.batch = batch;
        this.cursor = new JdbcTemplate(batch.getDataSource());
        this.cursor.setFetchSize(5000);
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    /**
     * @param lastHeartbeatAt last heard of, by any heartbeat ({@link #SEEN_AT})
     * @param contentAt       when {@code lastHeartbeat} came: behind a regional hub up to a minute before the last "alive"
     */
    public record BranchRow(String id, String name, String timezone, Instant firstSeenAt, Instant lastHeartbeatAt,
                            Instant contentAt, String lastHeartbeat, boolean sourceAttached,
                            @Nullable Integer configPublishedVersion, @Nullable Instant configPublishedAt,
                            @Nullable Integer configAppliedVersion) {
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

    /**
     * "Last heard of" of a branch, for {@code FROM }{@link #WITH_SEEN} (scale design 9.2): a heartbeat written whole sets
     * {@code branches.last_heartbeat_at}, one that only says "alive" sets {@code branch_seen}. Every judgement of
     * "online" reads it so.
     */
    public static final String SEEN_AT = "GREATEST(b.last_heartbeat_at, s.seen_at)";
    public static final String WITH_SEEN = "branches b LEFT JOIN branch_seen s ON s.branch_id = b.id";

    /**
     * Only "seen at": for heartbeats whose content waits for its resolution (scale design 9.2). One statement per chunk
     * into the narrow {@code branch_seen}: the wide row with the heartbeat JSON is not copied. An update for the branches
     * it has, an insert for the rest — an upsert of every row took twice as long.
     */
    public void touch(List<Heartbeat> heartbeats) {
        if (heartbeats.isEmpty()) {
            return;
        }
        var ids = heartbeats.stream().map(Heartbeat::branchId).toArray(String[]::new);
        var at = heartbeats.stream().map(h -> h.receivedAt().toString()).toArray(String[]::new);
        batch.update("""
                WITH v AS (SELECT id, at::timestamptz AS at FROM unnest(?::text[], ?::text[]) AS t(id, at)),
                     u AS (UPDATE branch_seen s SET seen_at = v.at FROM v WHERE s.branch_id = v.id AND s.seen_at < v.at)
                INSERT INTO branch_seen(branch_id, seen_at)
                SELECT v.id, v.at FROM v WHERE NOT EXISTS (SELECT 1 FROM branch_seen s WHERE s.branch_id = v.id)
                ON CONFLICT (branch_id) DO NOTHING""", ps -> {
            ps.setArray(1, ps.getConnection().createArrayOf("text", ids));
            ps.setArray(2, ps.getConnection().createArrayOf("text", at));
        });
    }

    /** Every branch is to be attached again: the event stream lost its sources. @return how many were attached */
    int detachAll() {
        return jdbc.sql("UPDATE branches SET source_attached=false WHERE source_attached").update();
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
        return jdbc.sql("SELECT id FROM branches").query(String.class).list();
    }

    /** A number from the last heartbeat, 0 when absent or not a number (like {@code JsonNode.asLong(0)}). */
    private static String num(String field, String type) {
        return "CASE WHEN jsonb_typeof(last_heartbeat->'" + field + "') = 'number' THEN (last_heartbeat->>'" + field
                + "')::numeric::" + type + " ELSE 0 END AS " + field;
    }

    /** Everything the overview judges a branch by, extracted by the database (C3c): no heartbeat JSON leaves it. */
    List<BranchStatus> statuses() {
        var out = new ArrayList<BranchStatus>();
        eachStatus(out::add);
        return out;
    }

    private static final String STATUSES = "SELECT id, name, " + SEEN_AT + " AS last_heartbeat_at, source_attached, "
            + num("waiting", "bigint") + ", " + num("overdue", "bigint") + ", " + num("oldest_wait_sec", "bigint") + ", "
            + num("windows_open", "bigint") + ", " + num("outbox_lag_sec", "bigint") + ", "
            + num("config_version", "int") + ", "
            + "last_heartbeat->>'load_level' AS load_level, last_heartbeat->>'load_level_since' AS load_level_since, "
            + "last_heartbeat->>'sent_at' AS sent_at, "
            + "CASE WHEN jsonb_typeof(last_heartbeat->'db_ok') = 'boolean' THEN (last_heartbeat->>'db_ok')::boolean ELSE true END AS db_ok, "
            + "config_published_version, config_published_at, config_applied_version, "
            + "last_heartbeat->>'events_via' AS events_via FROM " + WITH_SEEN + " ORDER BY id";

    /**
     * {@link #statuses()} one by one, from a cursor in a read-only transaction: at 250 000 branches the network is never
     * one list in memory — neither ours nor the driver's.
     */
    void eachStatus(Consumer<BranchStatus> consumer) {
        readOnly.executeWithoutResult(t -> cursor.query(STATUSES, (RowCallbackHandler) rs -> consumer.accept(status(rs))));
    }

    private static BranchStatus status(ResultSet rs) throws SQLException {
        var publishedAt = rs.getTimestamp("config_published_at");
        return new BranchStatus(rs.getString("id"), rs.getString("name"), rs.getTimestamp("last_heartbeat_at").toInstant(),
                rs.getBoolean("source_attached"), rs.getLong("waiting"), rs.getLong("overdue"), rs.getLong("oldest_wait_sec"),
                rs.getLong("windows_open"), rs.getLong("outbox_lag_sec"), rs.getString("load_level"),
                NetworkAnomalies.instant(rs.getString("load_level_since")), NetworkAnomalies.instant(rs.getString("sent_at")),
                rs.getBoolean("db_ok"), rs.getInt("config_version"), (Integer) rs.getObject("config_published_version"),
                publishedAt == null ? null : publishedAt.toInstant(), (Integer) rs.getObject("config_applied_version"),
                rs.getString("events_via"));
    }

    /** How many branches, counting no further than {@code max}: cheap whatever the size of the network. */
    public int countUpTo(int max) {
        return jdbc.sql("SELECT count(*) FROM (SELECT 1 FROM branches LIMIT :max) b").param("max", max)
                .query(Integer.class).single();
    }

    /** Full rows of a few branches — the cards of one page or of the worst list. */
    List<BranchRow> rows(List<String> ids) {
        if (ids.isEmpty()) { return List.of(); }
        return jdbc.sql(ROW + " WHERE b.id = ANY(:ids)").param("ids", ids.toArray(String[]::new)).query(BranchRegistry::row).list();
    }

    public List<BranchRow> all() {
        return jdbc.sql(ROW + " ORDER BY b.id").query(BranchRegistry::row).list();
    }

    private static final String ROW = "SELECT b.*, " + SEEN_AT + " AS heard_at, b.last_heartbeat::text AS hb FROM " + WITH_SEEN;

    private static BranchRow row(ResultSet rs, int n) throws SQLException {
        var publishedAt = rs.getTimestamp("config_published_at");
        return new BranchRow(rs.getString("id"), rs.getString("name"), rs.getString("timezone"),
                rs.getTimestamp("first_seen_at").toInstant(), rs.getTimestamp("heard_at").toInstant(),
                rs.getTimestamp("last_heartbeat_at").toInstant(), rs.getString("hb"), rs.getBoolean("source_attached"), (Integer) rs.getObject("config_published_version"),
                publishedAt == null ? null : publishedAt.toInstant(), (Integer) rs.getObject("config_applied_version"));
    }

    public boolean exists(String id) {
        return jdbc.sql("SELECT count(*) FROM branches WHERE id=:id").param("id", id).query(Long.class).single() > 0;
    }

    public Optional<BranchRow> get(String id) {
        return rows(List.of(id)).stream().findFirst();
    }
}
