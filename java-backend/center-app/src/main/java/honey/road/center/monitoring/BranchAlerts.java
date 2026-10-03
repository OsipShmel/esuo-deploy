package honey.road.center.monitoring;

import honey.road.center.monitoring.MonitoringRules.Alert;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code branch_alerts} (design 6.4): the result of the last rules evaluation, read by the network overview. */
@Repository
public class BranchAlerts {
    private final JdbcClient jdbc;
    private final JdbcTemplate batch;

    public BranchAlerts(JdbcClient jdbc, JdbcTemplate batch) {
        this.jdbc = jdbc; this.batch = batch;
    }

    /**
     * Makes the table equal to {@code alerts}; joins the caller's transaction. {@code since} is written as given: the
     * evaluator keeps it for a continuing alert. The alerts go as arrays, so any number of them fits one statement
     * (a list of keys stops at 65 535 JDBC parameters — a restarted swarm alone is more), and a row is rewritten only
     * when what a person sees has changed.
     */
    public void sync(List<Alert> alerts, Instant now) {
        int n = alerts.size();
        var branch = new String[n];
        var rule = new String[n];
        var severity = new String[n];
        var text = new String[n];
        var value = new Double[n];
        var since = new String[n];
        for (int i = 0; i < n; i++) {
            var a = alerts.get(i);
            branch[i] = a.branchId(); rule[i] = a.ruleId(); severity[i] = a.severity(); text[i] = a.text();
            value[i] = Double.isFinite(a.value()) ? a.value() : null; since[i] = a.since().toString();
        }
        batch.update("""
                DELETE FROM branch_alerts t WHERE NOT EXISTS (
                  SELECT 1 FROM unnest(?::text[], ?::text[]) a(branch_id, rule_id)
                  WHERE a.branch_id = t.branch_id AND a.rule_id = t.rule_id)""", ps -> {
            ps.setArray(1, ps.getConnection().createArrayOf("text", branch));
            ps.setArray(2, ps.getConnection().createArrayOf("text", rule));
        });
        if (n == 0) {
            return;
        }
        batch.update("""
                INSERT INTO branch_alerts(branch_id,rule_id,severity,text,value,since,updated_at)
                SELECT a.branch_id, a.rule_id, a.severity, a.text, coalesce(a.value, 0), a.since::timestamptz, ?
                FROM unnest(?::text[], ?::text[], ?::text[], ?::text[], ?::float8[], ?::text[])
                     a(branch_id, rule_id, severity, text, value, since)
                ON CONFLICT (branch_id, rule_id) DO UPDATE SET severity=EXCLUDED.severity, text=EXCLUDED.text,
                       value=EXCLUDED.value, since=EXCLUDED.since, updated_at=EXCLUDED.updated_at
                WHERE (branch_alerts.severity, branch_alerts.text, branch_alerts.since)
                      IS DISTINCT FROM (EXCLUDED.severity, EXCLUDED.text, EXCLUDED.since)""", ps -> {
            var c = ps.getConnection();
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setArray(2, c.createArrayOf("text", branch));
            ps.setArray(3, c.createArrayOf("text", rule));
            ps.setArray(4, c.createArrayOf("text", severity));
            ps.setArray(5, c.createArrayOf("text", text));
            ps.setArray(6, c.createArrayOf("float8", value));
            ps.setArray(7, c.createArrayOf("text", since));
        });
    }

    /** {@code since} of every stored alert by {@code branch/rule}: the evaluator keeps it for a continuing one. */
    public Map<String, Instant> sinceByKey() {
        var m = new java.util.HashMap<String, Instant>();
        jdbc.sql("SELECT branch_id, rule_id, since FROM branch_alerts")
                .query(rs -> { m.put(rs.getString("branch_id") + "/" + rs.getString("rule_id"), rs.getTimestamp("since").toInstant()); });
        return m;
    }

    /** Alerts per branch for the overview: {@code {code, severity, text, since}}, critical first. */
    public Map<String, List<Map<String, Object>>> byBranch() {
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        jdbc.sql("""
                        SELECT branch_id, rule_id, severity, text, since FROM branch_alerts
                        ORDER BY branch_id, CASE severity WHEN 'critical' THEN 0 WHEN 'warning' THEN 1 ELSE 2 END, since""")
                .query(rs -> {
                    var a = new LinkedHashMap<String, Object>();
                    a.put("code", rs.getString("rule_id"));
                    a.put("severity", rs.getString("severity"));
                    a.put("text", rs.getString("text"));
                    a.put("since", rs.getTimestamp("since").toInstant());
                    result.computeIfAbsent(rs.getString("branch_id"), k -> new ArrayList<>()).add(a);
                });
        return result;
    }
}
