package honey.road.center.monitoring;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code branch_metrics} (design 6.3): batch writes from the heartbeat flush, reads for the API, rules and cleanup. */
@Repository
public class BranchMetrics {
    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public BranchMetrics(JdbcTemplate jdbcTemplate, JdbcClient jdbc, JsonMapper json) {
        this.jdbcTemplate = jdbcTemplate; this.jdbc = jdbc; this.json = json;
    }

    /** Joins the caller's transaction: the flush writes branches and metrics together. */
    public void insert(List<MetricsRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
                        INSERT INTO branch_metrics(branch_id,ts,sys,queue,clock_skew_ms)
                        VALUES (?,?,CAST(? AS jsonb),CAST(? AS jsonb),?) ON CONFLICT DO NOTHING""",
                rows.stream().map(r -> new Object[]{r.branchId(), Timestamp.from(r.ts()),
                        r.sys() == null ? null : r.sys().toString(), r.queue().toString(), r.clockSkewMs()}).toList());
    }

    /** API rows in ascending time: {@code ts, sys, queue, clock_skew_ms}. */
    public List<Map<String, Object>> range(String branchId, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT ts, sys::text AS sys, queue::text AS queue, clock_skew_ms FROM branch_metrics
                        WHERE branch_id=:b AND ts >= :from AND ts < :to ORDER BY ts""")
                .param("b", branchId).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .query((rs, n) -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("ts", rs.getTimestamp("ts").toInstant());
                    m.put("sys", node(rs, "sys"));
                    m.put("queue", node(rs, "queue"));
                    m.put("clock_skew_ms", rs.getObject("clock_skew_ms"));
                    return (Map<String, Object>) m;
                })
                .list();
    }

    /** @return rows deleted */
    public int deleteOlderThan(Instant cut) {
        return jdbc.sql("DELETE FROM branch_metrics WHERE ts < :cut").param("cut", Timestamp.from(cut)).update();
    }

    private @Nullable JsonNode node(ResultSet rs, String column) throws SQLException {
        var text = rs.getString(column);
        return text == null ? null : json.readTree(text);
    }
}
