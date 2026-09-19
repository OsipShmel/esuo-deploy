package honey.road.node.queue.application;

import honey.road.node.config.NodeProperties;
import honey.road.node.persistence.CatalogRepository;
import honey.road.node.persistence.PolicyRepository;
import honey.road.node.persistence.WindowRepository;
import honey.road.node.queue.domain.DomainException;
import honey.road.node.queue.domain.PostalService;
import honey.road.node.queue.domain.ServiceWindow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Аналитика отделения (разделы 9.3 и 6.8) по локальной БД: работает при полной потере связи с центром.
 * <p>Определения (фиксируем, чтобы цифры на экране можно было объяснить):
 * <ul>
 *   <li><b>Период</b> — по времени выдачи талона {@code issued_at} ∈ [from, to); для окон — пересечение с периодом.</li>
 *   <li><b>Обслужен</b> — статус {@code done}.</li>
 *   <li><b>Ожидание</b> — от {@code checked_in_at} (для записи) или {@code issued_at} до <i>первого</i> события
 *       {@code ticket.called} из журнала; повторные вызовы и возвраты в очередь его не сбрасывают.</li>
 *   <li><b>Длительность обслуживания</b> — {@code completed_at − (serving_started_at | called_at)} у обслуженных.</li>
 *   <li><b>Окно открыто</b> — интервалы между {@code window.opened} (не повторным) и {@code window.closed}
 *       из журнала окна; простой = открыто − обслуживает.</li>
 * </ul>
 */
@Service
public class AnalyticsQueries {
    public record Period(Instant from, Instant to) {
    }

    public record Totals(int issued, int served, int cancelled, int noShow, int active,
                         Long avgWaitSec, Long maxWaitSec, Long avgServiceSec) {
    }

    public record SourceStat(String source, int issued, int served, Long avgWaitSec, Long maxWaitSec) {
    }

    public record ServiceStat(UUID serviceId, String serviceName, int issued, int served,
                              Long avgWaitSec, Long maxWaitSec, Long avgServiceSec) {
    }

    public record Summary(Instant from, Instant to, Totals totals, List<SourceStat> bySource, List<ServiceStat> byService) {
    }

    public record WindowStat(UUID windowId, String name, long openSec, long servingSec, long idleSec, int served,
                             Long avgServiceSec, double utilization) {
    }

    public record Deviations(Instant generatedAt, Instant from, Instant to, Map<String, Integer> counts,
                             List<Map<String, Object>> items) {
    }

    public record Unfinished(UUID ticketId, String displayNumber, String source, String status, UUID serviceId,
                             String serviceName, Instant issuedAt, Instant slotStart, Instant dueAt, UUID windowId) {
    }

    private record Row(String source, UUID serviceId, String status, Double waitSec, Double serviceSec) {
    }

    private final JdbcClient jdbc;
    private final NodeProperties node;
    private final CatalogRepository catalog;
    private final WindowRepository windows;
    private final PolicyRepository policies;
    private final Clock clock;

    public AnalyticsQueries(JdbcClient jdbc, NodeProperties node, CatalogRepository catalog, WindowRepository windows,
                            PolicyRepository policies, Clock clock) {
        this.jdbc = jdbc; this.node = node; this.catalog = catalog; this.windows = windows;
        this.policies = policies; this.clock = clock;
    }

    // ------------------------------------------------------------------ период

    /**
     * {@code from}/{@code to} — дата ({@code 2026-09-21}, по часовому поясу отделения) или момент ISO-8601.
     * По умолчанию — с начала сегодняшнего дня до текущего момента. Дата в {@code to} включает весь этот день.
     */
    public Period period(String from, String to) {
        var zone = ZoneId.of(catalog.branch(node.branchId()).timezone());
        var now = clock.instant();
        var start = from == null || from.isBlank() ? now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
                : parse(from, zone, false);
        var end = to == null || to.isBlank() ? now : parse(to, zone, true);
        if (!start.isBefore(end)) {
            throw DomainException.validation("from должен быть раньше to");
        }
        return new Period(start, end);
    }

    private static Instant parse(String value, ZoneId zone, boolean endExclusiveDay) {
        try {
            if (value.length() <= 10) {
                var day = LocalDate.parse(value);
                return (endExclusiveDay ? day.plusDays(1) : day).atStartOfDay(zone).toInstant();
            }
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw DomainException.validation("Ожидается дата 2026-09-21 или момент 2026-09-21T09:00:00Z");
        }
    }

    // ------------------------------------------------------------------ summary

    @Transactional(readOnly = true)
    public Summary summary(Period p) {
        var rows = jdbc.sql("""
                        SELECT t.source, t.service_id, t.status,
                               GREATEST(0, EXTRACT(EPOCH FROM (fc.first_called - COALESCE(t.checked_in_at, t.issued_at)))) AS wait_sec,
                               CASE WHEN t.status = 'done' THEN
                                    GREATEST(0, EXTRACT(EPOCH FROM (t.completed_at - COALESCE(t.serving_started_at, t.called_at)))) END AS service_sec
                          FROM tickets t
                          LEFT JOIN LATERAL (SELECT min(e.occurred_at) AS first_called FROM ticket_events e
                                              WHERE e.ticket_id = t.id AND e.event_type = 'ticket.called') fc ON true
                         WHERE t.branch_id = :b AND t.issued_at >= :from AND t.issued_at < :to
                        """)
                .param("b", node.branchId()).param("from", Timestamp.from(p.from())).param("to", Timestamp.from(p.to()))
                .query((rs, n) -> new Row(rs.getString("source"), rs.getObject("service_id", UUID.class), rs.getString("status"),
                        number(rs, "wait_sec"), number(rs, "service_sec")))
                .list();
        var names = catalog.services(node.branchId()).stream().collect(Collectors.toMap(PostalService::id, PostalService::name));

        var totals = new Totals(rows.size(), count(rows, "done"), count(rows, "cancelled"), count(rows, "no_show"),
                (int) rows.stream().filter(r -> List.of("booked", "waiting", "called", "serving").contains(r.status())).count(),
                avg(rows, Row::waitSec), max(rows, Row::waitSec), avg(rows, Row::serviceSec));
        var bySource = rows.stream().collect(Collectors.groupingBy(Row::source, java.util.TreeMap::new, Collectors.toList()))
                .entrySet().stream()
                .map(e -> new SourceStat(e.getKey(), e.getValue().size(), count(e.getValue(), "done"),
                        avg(e.getValue(), Row::waitSec), max(e.getValue(), Row::waitSec)))
                .toList();
        var byService = rows.stream().collect(Collectors.groupingBy(Row::serviceId)).entrySet().stream()
                .map(e -> new ServiceStat(e.getKey(), names.getOrDefault(e.getKey(), "?"), e.getValue().size(),
                        count(e.getValue(), "done"), avg(e.getValue(), Row::waitSec), max(e.getValue(), Row::waitSec),
                        avg(e.getValue(), Row::serviceSec)))
                .sorted(Comparator.comparing(ServiceStat::serviceName))
                .toList();
        return new Summary(p.from(), p.to(), totals, bySource, byService);
    }

    /** EXTRACT в PostgreSQL 14+ возвращает numeric, а не double precision — читаем как BigDecimal. */
    private static Double number(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        var v = rs.getBigDecimal(column);
        return v == null ? null : v.doubleValue();
    }

    private static int count(List<Row> rows, String status) {
        return (int) rows.stream().filter(r -> status.equals(r.status())).count();
    }

    private static Long avg(List<Row> rows, Function<Row, Double> f) {
        var values = rows.stream().map(f).filter(Objects::nonNull).toList();
        return values.isEmpty() ? null : Math.round(values.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    private static Long max(List<Row> rows, Function<Row, Double> f) {
        return rows.stream().map(f).filter(Objects::nonNull).mapToDouble(Double::doubleValue).boxed()
                .max(Double::compare).map(Math::round).orElse(null);
    }

    // ------------------------------------------------------------------ windows

    private record WindowEvent(UUID windowId, String type, Instant at, boolean reopened) {
    }

    @Transactional(readOnly = true)
    public List<WindowStat> windows(Period p) {
        var now = clock.instant();
        var events = jdbc.sql("""
                        SELECT e.window_id, e.event_type, e.occurred_at, COALESCE(e.payload->>'reopened', 'false') AS reopened
                          FROM window_events e JOIN windows w ON w.id = e.window_id
                         WHERE w.branch_id = :b AND e.occurred_at < :to ORDER BY e.occurred_at
                        """)
                .param("b", node.branchId()).param("to", Timestamp.from(p.to()))
                .query((rs, n) -> new WindowEvent(rs.getObject("window_id", UUID.class), rs.getString("event_type"),
                        rs.getTimestamp("occurred_at").toInstant(), Boolean.parseBoolean(rs.getString("reopened"))))
                .list().stream().collect(Collectors.groupingBy(WindowEvent::windowId));

        record Served(int n, double sec) {
        }
        var served = new HashMap<UUID, Served>();
        jdbc.sql("""
                        SELECT window_id, count(*) AS n,
                               COALESCE(sum(GREATEST(0, EXTRACT(EPOCH FROM (completed_at - COALESCE(serving_started_at, called_at))))), 0) AS sec
                          FROM tickets
                         WHERE branch_id = :b AND status = 'done' AND window_id IS NOT NULL
                           AND completed_at >= :from AND completed_at < :to
                         GROUP BY window_id
                        """)
                .param("b", node.branchId()).param("from", Timestamp.from(p.from())).param("to", Timestamp.from(p.to()))
                .query((rs, n) -> Map.entry(rs.getObject("window_id", UUID.class), new Served(rs.getInt("n"), rs.getDouble("sec"))))
                .list().forEach(e -> served.put(e.getKey(), e.getValue()));

        var opened = jdbc.sql("SELECT id, opened_at FROM windows WHERE branch_id = :b").param("b", node.branchId())
                .query((rs, n) -> Map.entry(rs.getObject("id", UUID.class),
                        rs.getTimestamp("opened_at") == null ? Instant.EPOCH : rs.getTimestamp("opened_at").toInstant()))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        var result = new ArrayList<WindowStat>();
        for (var w : windows.all(node.branchId())) {
            var list = events.getOrDefault(w.id(), List.of());
            long open = 0;
            Instant start = null;
            for (var e : list) {
                if ("window.opened".equals(e.type()) && !e.reopened() && start == null) {
                    start = e.at();
                } else if ("window.closed".equals(e.type()) && start != null) {
                    open += overlap(start, e.at(), p);
                    start = null;
                }
            }
            boolean currentlyOpen = "open".equals(w.status()) || "closing".equals(w.status());
            if (start == null && list.isEmpty() && currentlyOpen) {
                start = opened.getOrDefault(w.id(), Instant.EPOCH);      // окно, открытое без журнала (демо-данные)
            }
            if (start != null) {
                open += overlap(start, now, p);
            }
            var s = served.getOrDefault(w.id(), new Served(0, 0));
            long serving = Math.round(s.sec());
            long idle = Math.max(0, open - serving);
            result.add(new WindowStat(w.id(), w.name(), open, serving, idle, s.n(),
                    s.n() == 0 ? null : Math.round(s.sec() / s.n()), open == 0 ? 0 : Math.min(1.0, (double) serving / open)));
        }
        return result;
    }

    private static long overlap(Instant a, Instant b, Period p) {
        var from = a.isAfter(p.from()) ? a : p.from();
        var to = b.isBefore(p.to()) ? b : p.to();
        return Math.max(0, to.getEpochSecond() - from.getEpochSecond());
    }

    // ------------------------------------------------------------------ deviations

    @Transactional(readOnly = true)
    public Deviations deviations(Period p) {
        var now = clock.instant();
        var policy = policies.current();
        var items = new ArrayList<Map<String, Object>>();
        var branch = node.branchId();

        // Просроченные ожидающие: дедлайн прошёл, клиента ещё не вызвали (текущее состояние).
        jdbc.sql("""
                        SELECT id, display_number, service_id, source, due_at FROM tickets
                         WHERE branch_id = :b AND status = 'waiting' AND due_at < :now ORDER BY due_at
                        """).param("b", branch).param("now", Timestamp.from(now))
                .query((rs, n) -> {
                    var dueAt = rs.getTimestamp("due_at").toInstant();
                    return item("overdue_ticket", "ticket_id", rs.getObject("id", UUID.class),
                            "display_number", rs.getString("display_number"), "service_id", rs.getObject("service_id", UUID.class),
                            "source", rs.getString("source"), "due_at", dueAt, "overdue_sec", now.getEpochSecond() - dueAt.getEpochSecond());
                }).list().forEach(items::add);

        // Неявки за период.
        jdbc.sql("""
                        SELECT id, display_number, service_id, source, close_reason, updated_at FROM tickets
                         WHERE branch_id = :b AND status = 'no_show' AND updated_at >= :from AND updated_at < :to ORDER BY updated_at
                        """).param("b", branch).param("from", Timestamp.from(p.from())).param("to", Timestamp.from(p.to()))
                .query((rs, n) -> item("no_show", "ticket_id", rs.getObject("id", UUID.class),
                        "display_number", rs.getString("display_number"), "service_id", rs.getObject("service_id", UUID.class),
                        "source", rs.getString("source"), "reason", rs.getString("close_reason"),
                        "at", rs.getTimestamp("updated_at").toInstant()))
                .list().forEach(items::add);

        // Долгие обслуживания за период (порог — конфигурация).
        jdbc.sql("""
                        SELECT id, display_number, service_id, window_id, employee_id,
                               EXTRACT(EPOCH FROM (completed_at - COALESCE(serving_started_at, called_at))) AS sec
                          FROM tickets
                         WHERE branch_id = :b AND status = 'done' AND completed_at >= :from AND completed_at < :to
                           AND EXTRACT(EPOCH FROM (completed_at - COALESCE(serving_started_at, called_at))) > :limit
                         ORDER BY completed_at
                        """).param("b", branch).param("from", Timestamp.from(p.from())).param("to", Timestamp.from(p.to()))
                .param("limit", policy.longServiceMin() * 60L)
                .query((rs, n) -> item("long_service", "ticket_id", rs.getObject("id", UUID.class),
                        "display_number", rs.getString("display_number"), "service_id", rs.getObject("service_id", UUID.class),
                        "window_id", rs.getObject("window_id", UUID.class), "employee_id", rs.getObject("employee_id", UUID.class),
                        "duration_sec", Math.round(rs.getDouble("sec"))))
                .list().forEach(items::add);

        idleWindows(now, policy.idleAlertMin(), items);

        // S2: есть ожидающие, а открытого окна с их услугой нет.
        jdbc.sql("""
                        SELECT s.id, s.name, count(*) AS waiting FROM tickets t JOIN services s ON s.id = t.service_id
                         WHERE t.branch_id = :b AND t.status = 'waiting'
                           AND NOT EXISTS (SELECT 1 FROM window_services ws JOIN windows w ON w.id = ws.window_id
                                            WHERE ws.service_id = t.service_id AND w.status = 'open')
                         GROUP BY s.id, s.name ORDER BY s.name
                        """).param("b", branch)
                .query((rs, n) -> item("service_without_window", "service_id", rs.getObject("id", UUID.class),
                        "service_name", rs.getString("name"), "waiting", rs.getInt("waiting")))
                .list().forEach(items::add);

        // Открытые инциденты.
        jdbc.sql("""
                        SELECT id, kind, description, window_id, ticket_id, created_at FROM incidents
                         WHERE status = 'open' ORDER BY created_at
                        """)
                .query((rs, n) -> item("open_incident", "incident_id", rs.getObject("id", UUID.class),
                        "kind", rs.getString("kind"), "description", rs.getString("description"),
                        "window_id", rs.getObject("window_id", UUID.class), "ticket_id", rs.getObject("ticket_id", UUID.class),
                        "created_at", rs.getTimestamp("created_at").toInstant()))
                .list().forEach(items::add);

        var counts = new LinkedHashMap<String, Integer>();
        for (var type : List.of("overdue_ticket", "no_show", "long_service", "idle_window", "service_without_window", "open_incident")) {
            counts.put(type, (int) items.stream().filter(i -> type.equals(i.get("type"))).count());
        }
        return new Deviations(now, p.from(), p.to(), counts, items);
    }

    /** S14: окно {@code open} без активного талона и без действий дольше {@code idle_alert_min}. */
    private void idleWindows(Instant now, int idleAlertMin, List<Map<String, Object>> items) {
        var branch = node.branchId();
        var lastWindowEvent = jdbc.sql("SELECT window_id, max(occurred_at) AS at FROM window_events GROUP BY window_id")
                .query((rs, n) -> Map.entry(rs.getObject("window_id", UUID.class), rs.getTimestamp("at").toInstant()))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        var lastTicket = jdbc.sql("""
                        SELECT window_id, max(COALESCE(completed_at, called_at)) AS at FROM tickets
                         WHERE branch_id = :b AND window_id IS NOT NULL GROUP BY window_id
                        """).param("b", branch)
                .query((rs, n) -> Map.entry(rs.getObject("window_id", UUID.class),
                        rs.getTimestamp("at") == null ? Instant.EPOCH : rs.getTimestamp("at").toInstant()))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a.isAfter(b) ? a : b));
        var active = jdbc.sql("SELECT DISTINCT window_id FROM tickets WHERE branch_id = :b AND status IN ('called','serving')")
                .param("b", branch).query((rs, n) -> rs.getObject("window_id", UUID.class)).list();
        var openedAt = jdbc.sql("SELECT id, opened_at FROM windows WHERE branch_id = :b").param("b", branch)
                .query((rs, n) -> Map.entry(rs.getObject("id", UUID.class),
                        rs.getTimestamp("opened_at") == null ? Instant.EPOCH : rs.getTimestamp("opened_at").toInstant()))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        for (ServiceWindow w : windows.all(branch)) {
            if (!"open".equals(w.status()) || active.contains(w.id())) {
                continue;
            }
            var last = List.of(lastWindowEvent.getOrDefault(w.id(), Instant.EPOCH),
                    lastTicket.getOrDefault(w.id(), Instant.EPOCH), openedAt.getOrDefault(w.id(), Instant.EPOCH))
                    .stream().max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
            long idle = now.getEpochSecond() - last.getEpochSecond();
            if (idle > idleAlertMin * 60L) {
                items.add(item("idle_window", "window_id", w.id(), "window_name", w.name(),
                        "idle_sec", idle, "last_activity_at", last));
            }
        }
    }

    private static Map<String, Object> item(String type, Object... keyValues) {
        var m = new LinkedHashMap<String, Object>();
        m.put("type", type);
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    // ------------------------------------------------------------------ unfinished

    @Transactional(readOnly = true)
    public List<Unfinished> unfinished(Period p) {
        var names = catalog.services(node.branchId()).stream().collect(Collectors.toMap(PostalService::id, PostalService::name));
        return jdbc.sql("""
                        SELECT id, display_number, source, status, service_id, issued_at, slot_start, due_at, window_id FROM tickets
                         WHERE branch_id = :b AND status IN ('booked','waiting','called','serving')
                           AND issued_at >= :from AND issued_at < :to ORDER BY issued_at
                        """).param("b", node.branchId()).param("from", Timestamp.from(p.from())).param("to", Timestamp.from(p.to()))
                .query((rs, n) -> new Unfinished(rs.getObject("id", UUID.class), rs.getString("display_number"),
                        rs.getString("source"), rs.getString("status"), rs.getObject("service_id", UUID.class),
                        names.getOrDefault(rs.getObject("service_id", UUID.class), "?"),
                        rs.getTimestamp("issued_at").toInstant(),
                        rs.getTimestamp("slot_start") == null ? null : rs.getTimestamp("slot_start").toInstant(),
                        rs.getTimestamp("due_at") == null ? null : rs.getTimestamp("due_at").toInstant(),
                        rs.getObject("window_id", UUID.class)))
                .list();
    }
}
