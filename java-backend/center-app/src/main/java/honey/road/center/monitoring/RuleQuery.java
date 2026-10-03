package honey.road.center.monitoring;

import honey.road.center.monitoring.MonitoringConfig.Rule;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The monitoring rules as one SQL statement over {@code branch_metrics} (scale design 5.1): only what fires leaves the
 * database, so the center's memory does not grow with the network or the history. Built once from the rules; metric
 * names come from {@link MetricColumns} and operators from a fixed set (checked by {@link MonitoringConfig}), values
 * are plain numbers, so nothing from the file reaches the SQL as text.
 *
 * <ul>
 *   <li>{@code threshold}: one pass over the last {@code max(for)+FRESH} with order-free aggregates keeps only the
 *       branches that may fire (last point fresh, a point at least {@code for} old, nothing bad since it); for them
 *       the points are read by the primary key and the run is checked exactly — a gap over FRESH starts a new run,
 *       {@code since} is the start of the run that reaches the last point.</li>
 *   <li>{@code ratio}: the last point, if fresh.</li>
 *   <li>{@code increase}: the first and the last point of the window. A restart in between (the process start by the
 *       node's clock moved) adds the last value of the old process to the growth after it.</li>
 *   <li>{@code decrease} ({@code sys.uptime_sec} only): a restart within the window; {@code since} is the new
 *       process start by the node's clock — a heartbeat read late by a loaded center does not move it.</li>
 * </ul>
 * Every rule needs a fresh last point: a branch without heartbeats is {@code offline}, not "still hot".
 */
public class RuleQuery {
    /** Process starts this far apart are one process: the node's wall clock may be nudged by NTP. */
    static final Duration SAME_PROCESS = Duration.ofSeconds(5);
    private static final String NOW = "CAST(:now AS timestamptz)";
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Moscow");

    public record Fired(String branchId, Rule rule, double value, Instant since, ZoneId zone) {
    }

    private final JdbcClient jdbc;
    private final List<Rule> rules;
    private final @Nullable String sql;

    public RuleQuery(JdbcClient jdbc, List<Rule> rules, double durationScale, Duration fresh) {
        this.jdbc = jdbc;
        this.rules = List.copyOf(rules);
        this.sql = rules.isEmpty() ? null : build(this.rules, durationScale, fresh);
    }

    public List<Fired> fired(Instant now) {
        if (sql == null) {
            return List.of();
        }
        return jdbc.sql(sql).param("now", Timestamp.from(now))
                .query((rs, n) -> new Fired(rs.getString("branch_id"), rules.get(rs.getInt("rule")), rs.getDouble("value"),
                        rs.getTimestamp("since").toInstant(), zone(rs.getString("timezone"))))
                .list();
    }

    private static ZoneId zone(@Nullable String id) {
        try {
            return id == null ? DEFAULT_ZONE : ZoneId.of(id);
        } catch (DateTimeException e) {
            return DEFAULT_ZONE;
        }
    }

    private static String build(List<Rule> rules, double scale, Duration fresh) {
        var holdMax = rules.stream().filter(r -> "threshold".equals(r.kind()))
                .map(r -> MonitoringRules.scaled(r.holdFor(), scale)).max(Comparator.naturalOrder()).orElse(Duration.ZERO);
        var ctes = new ArrayList<String>();
        var aggregates = new ArrayList<String>();
        var parts = new ArrayList<String>();
        var freshAgo = ago(fresh);
        for (int i = 0; i < rules.size(); i++) {
            var r = rules.get(i);
            switch (r.kind()) {
                case "threshold" -> threshold(i, r, MonitoringRules.scaled(r.holdFor(), scale), fresh, ctes, aggregates, parts);
                case "ratio" -> {
                    aggregates.add("max(m.ts) FILTER (WHERE " + value(r, "m.") + " IS NOT NULL) AS last_" + i);
                    parts.add("SELECT s.branch_id, " + i + " AS rule, " + value(r, "m.") + " AS value, s.last_" + i + " AS since "
                            + "FROM s1 s JOIN branch_metrics m ON m.branch_id = s.branch_id AND m.ts = s.last_" + i
                            + " WHERE s.last_" + i + " >= " + freshAgo + " AND " + condition(r, value(r, "m.")));
                }
                case "increase" -> increase(i, r, MonitoringRules.scaled(r.window(), scale), freshAgo, ctes, parts);
                default -> decrease(i, r, MonitoringRules.scaled(r.window(), scale), freshAgo, parts);
            }
        }
        aggregates.add("max(m.ts) AS last_any");
        var s1 = "s1 AS (SELECT m.branch_id, " + String.join(", ", aggregates) + " FROM branch_metrics m WHERE m.ts > "
                + ago(holdMax.plus(fresh)) + " AND m.ts <= " + NOW + " GROUP BY m.branch_id)";
        ctes.addFirst(s1);
        return "WITH " + String.join(",\n", ctes) + "\nSELECT x.branch_id, x.rule, x.value, x.since, b.timezone FROM (\n"
                + String.join("\nUNION ALL\n", parts) + "\n) x LEFT JOIN branches b ON b.id = x.branch_id";
    }

    private static void threshold(int i, Rule r, Duration hold, Duration fresh, List<String> ctes, List<String> aggregates,
                                  List<String> parts) {
        var v = value(r, "m.");
        aggregates.add("max(m.ts) FILTER (WHERE " + v + " IS NOT NULL) AS last_" + i);
        aggregates.add("max(m.ts) FILTER (WHERE " + v + " IS NOT NULL AND m.ts <= " + ago(hold) + ") AS held_" + i);
        aggregates.add("max(m.ts) FILTER (WHERE " + v + " IS NOT NULL AND NOT (" + condition(r, v) + ")) AS bad_" + i);
        // the branches that may fire: last point fresh, a point at least `for` old, nothing bad since that point
        ctes.add("c" + i + " AS (SELECT branch_id FROM s1 WHERE last_" + i + " >= " + ago(fresh)
                + " AND held_" + i + " >= " + ago(hold.plus(fresh)) + " AND (bad_" + i + " IS NULL OR bad_" + i + " < held_" + i + "))");
        // their points within the window, with the gap to the previous one
        ctes.add("t" + i + " AS (SELECT p.branch_id, p.ts, p.v, p.ok, p.ts - lag(p.ts) OVER (PARTITION BY p.branch_id ORDER BY p.ts) AS gap "
                + "FROM (SELECT m.branch_id, m.ts, " + v + " AS v, " + condition(r, v) + " AS ok FROM branch_metrics m "
                + "JOIN c" + i + " USING (branch_id) WHERE m.ts > " + ago(hold.plus(fresh)) + " AND m.ts <= " + NOW
                + " AND " + v + " IS NOT NULL) p)");
        // what breaks the run: the last bad point, the last point after a gap
        ctes.add("b" + i + " AS (SELECT branch_id, max(ts) FILTER (WHERE NOT ok) AS bad, "
                + "max(ts) FILTER (WHERE gap > " + interval(fresh) + ") AS gapped FROM t" + i + " GROUP BY branch_id)");
        parts.add("SELECT t.branch_id, " + i + " AS rule, (array_agg(t.v ORDER BY t.ts DESC))[1] AS value, min(t.ts) AS since "
                + "FROM t" + i + " t JOIN b" + i + " b USING (branch_id) "
                + "WHERE (b.bad IS NULL OR t.ts > b.bad) AND (b.gapped IS NULL OR t.ts >= b.gapped) "
                + "GROUP BY t.branch_id HAVING min(t.ts) <= " + ago(hold));
    }

    private static void increase(int i, Rule r, Duration window, String freshAgo, List<String> ctes, List<String> parts) {
        var v = value(r, "m.");
        var inWindow = "m.ts > " + ago(window) + " AND m.ts <= " + NOW + " AND " + v + " IS NOT NULL";
        var same = interval(SAME_PROCESS);
        // first and last point of the window by the primary key, and whether the process restarted in between
        ctes.add("e" + i + " AS (SELECT s.branch_id, f.ts AS f_ts, f.v AS f_v, f.st AS f_st, l.ts AS l_ts, l.v AS l_v, l.st AS l_st, "
                + "coalesce(l.st > f.st + " + same + ", false) AS restarted FROM s1 s "
                + "CROSS JOIN LATERAL (SELECT m.ts, " + v + " AS v, m.started_at AS st FROM branch_metrics m "
                + "WHERE m.branch_id = s.branch_id AND " + inWindow + " ORDER BY m.ts LIMIT 1) f "
                + "CROSS JOIN LATERAL (SELECT m.ts, " + v + " AS v, m.started_at AS st FROM branch_metrics m "
                + "WHERE m.branch_id = s.branch_id AND " + inWindow + " ORDER BY m.ts DESC LIMIT 1) l "
                + "WHERE s.last_any >= " + freshAgo + " AND l.ts >= " + freshAgo + " AND l.ts > f.ts)");
        // the growth: after a restart, the old process's last value counts too; a drop without a known start is a reset
        ctes.add("g" + i + " AS (SELECT e.*, CASE WHEN e.restarted THEN greatest(coalesce(pre.v, e.f_v) - e.f_v, 0) + e.l_v "
                + "WHEN e.l_v >= e.f_v THEN e.l_v - e.f_v ELSE e.l_v END AS value FROM e" + i + " e "
                + "LEFT JOIN LATERAL (SELECT " + v + " AS v FROM branch_metrics m WHERE m.branch_id = e.branch_id "
                + "AND m.ts >= e.f_ts AND m.ts < e.l_ts AND m.started_at < e.l_st - " + same + " AND " + v + " IS NOT NULL "
                + "ORDER BY m.ts DESC LIMIT 1) pre ON e.restarted)");
        // since: the first point that grew — over the first point in its process, over zero in a later one
        parts.add("SELECT g.branch_id, " + i + " AS rule, g.value, coalesce(x.since, g.l_ts) AS since "
                + "FROM (SELECT * FROM g" + i + " WHERE " + condition(r, "value") + ") g "
                + "CROSS JOIN LATERAL (SELECT min(m.ts) AS since FROM branch_metrics m WHERE m.branch_id = g.branch_id "
                + "AND m.ts > g.f_ts AND m.ts <= g.l_ts AND " + v + " IS NOT NULL AND CASE WHEN m.started_at IS NOT NULL "
                + "AND g.f_st IS NOT NULL AND abs(extract(epoch FROM m.started_at - g.f_st)) > " + SAME_PROCESS.toSeconds()
                + " THEN " + v + " > 0 ELSE " + v + " > g.f_v END) x");
    }

    private static void decrease(int i, Rule r, Duration window, String freshAgo, List<String> parts) {
        var v = value(r, "m.");
        var inWindow = "m.ts > " + ago(window) + " AND m.ts <= " + NOW + " AND " + v + " IS NOT NULL AND m.started_at IS NOT NULL";
        parts.add("SELECT s.branch_id, " + i + " AS rule, l.v AS value, l.st AS since FROM s1 s "
                + "CROSS JOIN LATERAL (SELECT m.started_at AS st FROM branch_metrics m "
                + "WHERE m.branch_id = s.branch_id AND " + inWindow + " ORDER BY m.ts LIMIT 1) f "
                + "CROSS JOIN LATERAL (SELECT m.ts, " + v + " AS v, m.started_at AS st FROM branch_metrics m "
                + "WHERE m.branch_id = s.branch_id AND " + inWindow + " ORDER BY m.ts DESC LIMIT 1) l "
                + "WHERE s.last_any >= " + freshAgo + " AND l.ts >= " + freshAgo + " AND l.st > f.st + " + interval(SAME_PROCESS));
    }

    /** The rule's value as float8 for the row alias {@code m.}: a column, a ratio of two, the clock skew in seconds. */
    static String value(Rule r, String alias) {
        var v = "ratio".equals(r.kind())
                ? "(CASE WHEN " + column(r.of(), alias) + " > 0 THEN " + column(r.metric(), alias) + " / " + column(r.of(), alias) + " END)"
                : column(r.metric(), alias);
        return r.abs() ? "abs(" + v + ")" : v;
    }

    private static String column(@Nullable String metric, String alias) {
        if (MonitoringConfig.CLOCK_SKEW.equals(metric)) {
            return "(" + alias + MetricColumns.CLOCK_SKEW_MS + " / 1000.0)";
        }
        var name = metric == null ? "" : metric.substring("sys.".length());
        var c = MetricColumns.SYS.stream().filter(x -> x.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown metric " + metric));
        return c.type() == MetricColumns.Type.BOOL ? "(" + alias + name + ")::int::float8" : alias + name + "::float8";
    }

    private static String condition(Rule r, String v) {
        if (r.op() == null || !MonitoringConfig.OPERATORS.contains(r.op()) || r.value() == null) {
            throw new IllegalArgumentException("monitoring rule " + r.id() + " has no condition");
        }
        return "(" + v + " " + r.op() + " " + BigDecimal.valueOf(r.value()).toPlainString() + ")";
    }

    private static String ago(Duration d) {
        return "(" + NOW + " - " + interval(d) + ")";
    }

    private static String interval(Duration d) {
        return "interval '" + d.toMillis() + " milliseconds'";
    }
}
