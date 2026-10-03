package honey.road.node.monitoring;

import com.zaxxer.hikari.HikariDataSource;
import honey.road.contracts.event.Subjects;
import honey.road.node.config.JsonCodec;
import honey.road.node.config.NodeProperties;
import honey.road.node.messaging.nats.BranchStreams;
import honey.road.node.messaging.nats.LeafLinkMonitor;
import honey.road.node.messaging.nats.NatsGateway;
import honey.road.node.messaging.outbox.OutboxWorker;
import honey.road.node.monitoring.DiagnosticsSnapshot.Section;
import honey.road.node.persistence.PolicyRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * The sections of a node snapshot (design 7): app, jvm, db_pool, db, outbox, nats, config, disk, lifecycle. Each one reads its
 * source directly and may throw; {@link DiagnosticsSnapshot} keeps a failure inside its section.
 */
@Component
public class SnapshotSections {
    private static final long MB = 1024 * 1024;
    private static final int STACK_FRAMES = 10;
    private static final int LIFECYCLE_LINES = 20;

    private final NodeProperties node;
    private final DataSource dataSource;
    private final OutboxWorker outbox;
    private final NatsGateway nats;
    private final LeafLinkMonitor leaf;
    private final BranchStreams streams;
    private final PolicyRepository policies;
    private final JsonCodec json;
    private final String logFile;
    private final Clock clock;

    public SnapshotSections(NodeProperties node, DataSource dataSource, OutboxWorker outbox, NatsGateway nats,
                            LeafLinkMonitor leaf, BranchStreams streams, PolicyRepository policies, JsonCodec json,
                            @Value("${esuo.logs.file:}") String logFile, @Qualifier("realClock") Clock clock) {
        this.node = node; this.dataSource = dataSource;
        this.outbox = outbox; this.nats = nats; this.leaf = leaf; this.streams = streams; this.policies = policies;
        this.json = json; this.logFile = logFile; this.clock = clock;
    }

    public List<Section> all() {
        return List.of(new Section("app", this::app), new Section("jvm", this::jvm),
                new Section("db_pool", this::dbPool), new Section("db", this::db),
                new Section("outbox", this::outbox), new Section("nats", this::nats), new Section("config", this::config),
                new Section("disk", this::disk), new Section("lifecycle", this::lifecycle));
    }

    private Object app() {
        var runtime = ManagementFactory.getRuntimeMXBean();
        var m = new LinkedHashMap<String, Object>();
        m.put("branch_id", node.branchId());
        m.put("version", LifecycleEvents.version());
        m.put("started_at", Instant.ofEpochMilli(runtime.getStartTime()));
        m.put("uptime_sec", runtime.getUptime() / 1000);
        m.put("demo_mode", node.demoMode());
        m.put("demo_data", node.demoData());
        return m;
    }

    /** Platform threads only: the JVM does not list virtual threads, and HTTP requests run on those. */
    private Object jvm() {
        var threads = ManagementFactory.getThreadMXBean();
        var infos = threads.dumpAllThreads(true, true);
        var byState = new TreeMap<String, Integer>();
        var blocked = new ArrayList<Map<String, Object>>();
        for (var t : infos) {
            if (t == null) {
                continue;
            }
            byState.merge(t.getThreadState().name(), 1, Integer::sum);
            if (t.getThreadState() == Thread.State.BLOCKED || t.getLockOwnerName() != null) {
                blocked.add(threadView(t));
            }
        }
        var deadlocked = threads.findDeadlockedThreads();
        var deadlocks = deadlocked == null ? List.<String>of() : Arrays.stream(threads.getThreadInfo(deadlocked))
                .filter(t -> t != null).map(ThreadInfo::getThreadName).toList();
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var m = new LinkedHashMap<String, Object>();
        m.put("threads", infos.length);
        m.put("threads_by_state", byState);
        m.put("virtual_threads_listed", false);
        m.put("blocked", blocked);
        m.put("deadlocks", deadlocks);
        m.put("heap_used_mb", heap.getUsed() / MB);
        m.put("heap_max_mb", heap.getMax() < 0 ? null : heap.getMax() / MB);
        return m;
    }

    private static Map<String, Object> threadView(ThreadInfo t) {
        var m = new LinkedHashMap<String, Object>();
        m.put("name", t.getThreadName());
        m.put("state", t.getThreadState().name());
        m.put("lock", t.getLockName());
        m.put("owner", t.getLockOwnerName());
        m.put("stack", Arrays.stream(t.getStackTrace()).limit(STACK_FRAMES).map(StackTraceElement::toString).toList());
        return m;
    }

    /**
     * Its own section: read from the pool without touching the database, it survives exactly the case a snapshot is
     * taken for — a locked database, where {@code waiting} is the number worth seeing.
     */
    private Object dbPool() {
        if (!(dataSource instanceof HikariDataSource hikari) || hikari.getHikariPoolMXBean() == null) {
            throw new IllegalStateException("not a Hikari pool");
        }
        var pool = hikari.getHikariPoolMXBean();
        return Map.of("active", pool.getActiveConnections(), "idle", pool.getIdleConnections(),
                "waiting", pool.getThreadsAwaitingConnection(), "max", hikari.getMaximumPoolSize());
    }

    /**
     * Over its own connection, outside the pool: a snapshot is wanted exactly when locked requests hold every pooled
     * connection. Each query has a 2 s statement timeout (a slow database); the socket timeout ends a read from a frozen
     * one, so the section's thread does not hang until the database comes back.
     */
    private Object db() {
        var m = new LinkedHashMap<String, Object>();
        try (var connection = diagnosticsConnection()) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("SET statement_timeout = '2s'");
            m.put("activity", part(() -> jdbc.queryForList("""
                    SELECT pid, state, wait_event_type, wait_event,
                           EXTRACT(EPOCH FROM now() - query_start)::int AS running_sec, left(query, 200) AS query
                    FROM pg_stat_activity
                    WHERE datname = current_database() AND pid <> pg_backend_pid() AND state IS DISTINCT FROM 'idle'
                    ORDER BY query_start NULLS LAST LIMIT 50""")));
            m.put("blocking", part(() -> jdbc.query("""
                    SELECT pid, pg_blocking_pids(pid) AS blocked_by, EXTRACT(EPOCH FROM now() - query_start)::int AS waiting_sec,
                           left(query, 200) AS query
                    FROM pg_stat_activity WHERE cardinality(pg_blocking_pids(pid)) > 0""", (rs, n) -> {
                var row = new LinkedHashMap<String, Object>();
                row.put("pid", rs.getInt("pid"));
                row.put("blocked_by", List.of((Integer[]) rs.getArray("blocked_by").getArray()));
                row.put("waiting_sec", rs.getObject("waiting_sec"));
                row.put("query", rs.getString("query"));
                return row;
            })));
            // sizes need a share lock on each table: under an exclusive lock this part times out, the rest stays
            m.put("largest_tables", part(() -> jdbc.queryForList("""
                    SELECT relname AS table, round(pg_total_relation_size(relid) / 1048576.0, 1) AS size_mb
                    FROM pg_stat_user_tables ORDER BY pg_total_relation_size(relid) DESC LIMIT 10""")));
            m.put("size_mb", part(() -> jdbc.queryForObject("SELECT pg_database_size(current_database()) / 1048576", Long.class)));
        } catch (SQLException e) {
            throw new IllegalStateException("database: " + e.getMessage(), e);
        }
        return m;
    }

    /** One query of a section: its failure stays in its place, like a failed section in the snapshot. */
    private static Object part(Supplier<Object> query) {
        try {
            return query.get();
        } catch (RuntimeException e) {
            Throwable cause = e;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            return Map.of("error", cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    private Connection diagnosticsConnection() throws SQLException {
        if (!(dataSource instanceof HikariDataSource hikari)) {
            return dataSource.getConnection();
        }
        var props = new Properties();
        if (hikari.getUsername() != null) {
            props.setProperty("user", hikari.getUsername());
        }
        if (hikari.getPassword() != null) {
            props.setProperty("password", hikari.getPassword());
        }
        props.setProperty("connectTimeout", "2");                      // seconds, pgjdbc
        props.setProperty("socketTimeout", Long.toString(DiagnosticsSnapshot.DEADLINE.toSeconds()));
        props.setProperty("ApplicationName", "esuo-diagnostics");
        return DriverManager.getConnection(hikari.getJdbcUrl(), props);
    }

    private Object outbox() {
        var stats = outbox.stats();
        return Map.of("pending", stats.pending(), "oldest_age_sec", stats.oldestAge().toSeconds());
    }

    private Object nats() {
        var m = new LinkedHashMap<String, Object>();
        m.put("connected", nats.isConnected());
        m.put("state", nats.state());
        m.put("state_since", nats.stateSince());
        m.put("url", nats.url());
        m.put("reconnects", nats.reconnects() < 0 ? null : nats.reconnects());
        m.put("leaf_linked", leaf.linked());
        m.put("leaf_rtt_ms", leaf.rttMs());
        m.put("streams", Map.of(Subjects.STREAM_BRANCH_EVT, streams.branchEvtReady(),
                Subjects.STREAM_BRANCH_INBOX, streams.branchInboxReady(), Subjects.STREAM_BRANCH_LOG, streams.branchLogReady()));
        return m;
    }

    private Object config() {
        var v = policies.versions();
        return Map.of("central_version", v.central(), "effective_version", v.effective());
    }

    private Object disk() {
        var dir = (logFile.isBlank() ? Path.of("") : Path.of(logFile).toAbsolutePath().getParent()).toAbsolutePath().toFile();
        return Map.of("path", dir.getPath(), "free_mb", dir.getUsableSpace() / MB, "total_mb", dir.getTotalSpace() / MB);
    }

    /** The last lifecycle events of the past day from the log file, oldest first. */
    private Object lifecycle() {
        if (logFile.isBlank()) {
            throw new IllegalStateException("no log file on this node (LOG_FILE is empty)");
        }
        var now = clock.instant();
        var query = new LogFileReader.Query(now.minus(Duration.ofDays(1)), now.plusSeconds(60), "trace",
                "\"kind\":\"lifecycle\"", 20_000, 512 * 1024);
        var last = new ArrayDeque<String>(LIFECYCLE_LINES);
        String cursor = null;
        for (int page = 0; page < 20; page++) {
            var p = LogFileReader.read(Path.of(logFile), query, cursor);
            for (var line : p.lines()) {
                if (last.size() == LIFECYCLE_LINES) {
                    last.removeFirst();
                }
                last.addLast(line);
            }
            cursor = p.nextCursor();
            if (cursor == null) {
                break;
            }
        }
        var events = new ArrayList<Map<String, Object>>();
        for (var line : last) {
            var j = json.readTree(line);
            var e = new LinkedHashMap<String, Object>();
            e.put("time", j.path("@timestamp").asString(null));
            e.put("level", j.path("log").path("level").asString(null));
            e.put("action", j.path("event").path("action").asString(null));
            e.put("message", j.path("message").asString(null));
            events.add(e);
        }
        return Map.of("events", events);
    }
}
