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

    /** Makes the table equal to {@code alerts}; joins the caller's transaction. A continuing alert keeps its {@code since}. */
    public void sync(List<Alert> alerts, Instant now) {
        if (alerts.isEmpty()) {
            jdbc.sql("DELETE FROM branch_alerts").update();
            return;
        }
        jdbc.sql("DELETE FROM branch_alerts WHERE branch_id || '/' || rule_id NOT IN (:keys)")
                .param("keys", alerts.stream().map(a -> a.branchId() + "/" + a.ruleId()).toList()).update();
        batch.batchUpdate("""
                        INSERT INTO branch_alerts(branch_id,rule_id,severity,text,value,since,updated_at)
                        VALUES (?,?,?,?,?,?,?)
                        ON CONFLICT (branch_id, rule_id) DO UPDATE SET severity=EXCLUDED.severity, text=EXCLUDED.text,
                               value=EXCLUDED.value, updated_at=EXCLUDED.updated_at""",
                alerts.stream().map(a -> new Object[]{a.branchId(), a.ruleId(), a.severity(), a.text(), a.value(),
                        Timestamp.from(a.since()), Timestamp.from(now)}).toList());
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
