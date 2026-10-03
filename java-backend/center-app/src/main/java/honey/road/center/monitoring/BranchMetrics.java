package honey.road.center.monitoring;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code branch_metrics} (design 6.3, scale design 5.1): batch writes from the heartbeat flush and the API reads. The
 * rules read it in SQL ({@link RuleQuery}), old hours go with their partitions ({@link MetricsPartitions}).
 */
@Repository
public class BranchMetrics {
    private static final String INSERT = "INSERT INTO branch_metrics(branch_id,ts," + String.join(",", MetricColumns.ALL)
            + ") VALUES (?,?" + ",?".repeat(MetricColumns.ALL.size()) + ") ON CONFLICT DO NOTHING";
    private static final String SELECT = "SELECT ts," + String.join(",", MetricColumns.ALL) + " FROM branch_metrics";
    /** SQL types of {@link MetricColumns#ALL}: a NULL is bound with its column's type, no driver round trip to guess it. */
    private static final int[] TYPES = MetricColumns.ALL.stream().mapToInt(BranchMetrics::sqlType).toArray();

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbc;

    public BranchMetrics(JdbcTemplate jdbcTemplate, JdbcClient jdbc) {
        this.jdbcTemplate = jdbcTemplate; this.jdbc = jdbc;
    }

    /** Joins the caller's transaction: the flush writes branches and metrics together. */
    public void insert(List<MetricsRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int n) throws SQLException {
                var r = rows.get(n);
                ps.setString(1, r.branchId());
                ps.setTimestamp(2, Timestamp.from(r.ts()));
                for (int i = 0; i < TYPES.length; i++) {
                    var v = r.values()[i];
                    if (v == null) {
                        ps.setNull(i + 3, TYPES[i]);
                    } else {
                        ps.setObject(i + 3, v instanceof Instant t ? Timestamp.from(t) : v, TYPES[i]);
                    }
                }
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    private static int sqlType(String column) {
        if (column.equals(MetricColumns.STARTED_AT)) {
            return Types.TIMESTAMP;
        }
        if (column.equals(MetricColumns.CLOCK_SKEW_MS)) {
            return Types.INTEGER;
        }
        var c = MetricColumns.SYS.stream().filter(x -> x.name().equals(column)).findFirst()
                .orElseGet(() -> MetricColumns.QUEUE.stream().filter(x -> x.name().equals(column)).findFirst().orElseThrow());
        return switch (c.type()) {
            case INT -> Types.INTEGER;
            case BIGINT -> Types.BIGINT;
            case REAL -> Types.REAL;
            case BOOL -> Types.BOOLEAN;
            case TEXT -> Types.VARCHAR;
        };
    }

    /**
     * API rows in ascending time, in the shape the heartbeat had: {@code ts}, {@code sys} (null for a node without
     * the sys block), {@code queue}, {@code clock_skew_ms}.
     */
    public List<Map<String, Object>> range(String branchId, Instant from, Instant to) {
        return jdbc.sql(SELECT + " WHERE branch_id=:b AND ts >= :from AND ts < :to ORDER BY ts")
                .param("b", branchId).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .query((rs, n) -> apiRow(rs))
                .list();
    }

    private static Map<String, Object> apiRow(ResultSet rs) throws SQLException {
        var sys = new LinkedHashMap<String, Object>();
        for (var c : MetricColumns.SYS) {
            put(sys, c.name(), rs.getObject(c.name()));
        }
        var started = rs.getTimestamp(MetricColumns.STARTED_AT);
        if (started != null) {
            sys.put(MetricColumns.STARTED_AT, started.toInstant());
        }
        var queue = new LinkedHashMap<String, Object>();
        for (var c : MetricColumns.QUEUE) {
            put(queue, c.name(), rs.getObject(c.name()));
        }
        var m = new LinkedHashMap<String, Object>();
        m.put("ts", rs.getTimestamp("ts").toInstant());
        m.put("sys", sys.isEmpty() ? null : sys);
        m.put("queue", queue);
        m.put("clock_skew_ms", rs.getObject(MetricColumns.CLOCK_SKEW_MS));
        return m;
    }

    private static void put(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value);
        }
    }
}
