package honey.road.center.centralconfig;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Center config tables (sect 8.4): global config, branch overrides, staff, version journal, apply state. */
@Repository
class ConfigStore {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonMapper json;

    ConfigStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc; this.json = json;
    }

    record Sections(@Nullable Map<String, Object> queue, @Nullable Map<String, Object> bounds,
                    @Nullable Map<String, Object> planning, @Nullable Instant updatedAt, @Nullable String updatedBy) {
        static Sections empty() { return new Sections(null, null, null, null, null); }
    }

    record Employee(UUID id, String fullName, boolean active) {
    }

    record User(UUID id, String login, String passwordHash, String role, @Nullable UUID employeeId,
                @Nullable String fullName, boolean active) {
    }

    record BranchState(String id, Instant lastHeartbeatAt, int heartbeatConfigVersion,
                       @Nullable Integer published, @Nullable Instant sentAt,
                       @Nullable Integer applied, @Nullable Instant appliedAt) {
    }

    record VersionRow(int version, String branchId, Map<String, Object> message, @Nullable String author, Instant createdAt) {
    }

    // ------------------------------------------------------------------ sections

    Sections global() {
        return jdbc.sql("SELECT queue::text q, bounds::text b, planning::text p, updated_at, updated_by FROM config_global WHERE id=1")
                .query(this::sections).single();
    }

    Sections branch(String branchId) {
        return jdbc.sql("SELECT queue::text q, bounds::text b, planning::text p, updated_at, updated_by FROM config_branch WHERE branch_id=:b")
                .param("b", branchId).query(this::sections).optional().orElse(Sections.empty());
    }

    void saveGlobal(Sections s, String author, Instant now) {
        jdbc.sql("""
                        UPDATE config_global SET queue=CAST(:q AS jsonb), bounds=CAST(:b AS jsonb), planning=CAST(:p AS jsonb),
                               updated_at=:now, updated_by=:by WHERE id=1
                        """)
                .param("q", write(orEmpty(s.queue()))).param("b", write(orEmpty(s.bounds())))
                .param("p", write(orEmpty(s.planning()))).param("now", Timestamp.from(now)).param("by", author)
                .update();
    }

    void saveBranch(String branchId, Sections s, String author, Instant now) {
        jdbc.sql("""
                        INSERT INTO config_branch(branch_id, queue, bounds, planning, updated_at, updated_by)
                        VALUES (:id, CAST(:q AS jsonb), CAST(:b AS jsonb), CAST(:p AS jsonb), :now, :by)
                        ON CONFLICT (branch_id) DO UPDATE SET queue=EXCLUDED.queue, bounds=EXCLUDED.bounds,
                               planning=EXCLUDED.planning, updated_at=EXCLUDED.updated_at, updated_by=EXCLUDED.updated_by
                        """)
                .param("id", branchId).param("q", writeNullable(s.queue())).param("b", writeNullable(s.bounds()))
                .param("p", writeNullable(s.planning())).param("now", Timestamp.from(now)).param("by", author)
                .update();
    }

    // ------------------------------------------------------------------ staff

    List<Employee> employees(String branchId) {
        return jdbc.sql("SELECT id, full_name, active FROM branch_employees WHERE branch_id=:b ORDER BY created_at, id")
                .param("b", branchId)
                .query((rs, n) -> new Employee(rs.getObject("id", UUID.class), rs.getString("full_name"), rs.getBoolean("active")))
                .list();
    }

    List<User> users(String branchId) {
        return jdbc.sql("""
                        SELECT u.id, u.login, u.password_hash, u.role, u.employee_id, e.full_name, u.active
                          FROM branch_users u LEFT JOIN branch_employees e ON e.id = u.employee_id
                         WHERE u.branch_id=:b ORDER BY u.created_at, u.id
                        """)
                .param("b", branchId).query(ConfigStore::user).list();
    }

    Optional<User> user(String branchId, UUID id) {
        return users(branchId).stream().filter(u -> u.id().equals(id)).findFirst();
    }

    boolean loginTaken(String branchId, String login) {
        return jdbc.sql("SELECT count(*) FROM branch_users WHERE branch_id=:b AND login=:l")
                .param("b", branchId).param("l", login).query(Long.class).single() > 0;
    }

    void insertEmployee(UUID id, String branchId, String fullName, Instant now) {
        jdbc.sql("INSERT INTO branch_employees(id, branch_id, full_name, active, created_at) VALUES (:id,:b,:n,true,:now)")
                .param("id", id).param("b", branchId).param("n", fullName).param("now", Timestamp.from(now)).update();
    }

    void insertUser(UUID id, String branchId, String login, String hash, String role, UUID employeeId, Instant now) {
        jdbc.sql("""
                        INSERT INTO branch_users(id, branch_id, login, password_hash, role, employee_id, active, created_at, updated_at)
                        VALUES (:id,:b,:l,:h,:r,:e,true,:now,:now)
                        """)
                .param("id", id).param("b", branchId).param("l", login).param("h", hash).param("r", role)
                .param("e", employeeId).param("now", Timestamp.from(now)).update();
    }

    void updateUser(UUID id, @Nullable String hash, @Nullable String role, @Nullable Boolean active, Instant now) {
        jdbc.sql("""
                        UPDATE branch_users SET password_hash=COALESCE(:h, password_hash), role=COALESCE(:r, role),
                               active=COALESCE(:a, active), updated_at=:now WHERE id=:id
                        """)
                .param("id", id).param("h", hash).param("r", role).param("a", active).param("now", Timestamp.from(now))
                .update();
    }

    // ------------------------------------------------------------------ versions and apply state

    boolean branchExists(String branchId) {
        return jdbc.sql("SELECT count(*) FROM branches WHERE id=:b").param("b", branchId).query(Long.class).single() > 0;
    }

    int nextVersion() {
        return jdbc.sql("SELECT nextval('config_version_seq')").query(Long.class).single().intValue();
    }

    /** Moves the sequence past a version a node already has (center DB was reset, node was not). */
    void advanceVersionPast(int version) {
        jdbc.sql("SELECT setval('config_version_seq', GREATEST((SELECT last_value FROM config_version_seq), :v))")
                .param("v", (long) version).query(Long.class).single();
    }

    void recordVersion(int version, String branchId, Map<String, Object> message, String author, Instant now) {
        jdbc.sql("""
                        INSERT INTO config_versions(version, branch_id, message, author, created_at)
                        VALUES (:v, :b, CAST(:m AS jsonb), :a, :now)
                        """)
                .param("v", version).param("b", branchId).param("m", write(message)).param("a", author)
                .param("now", Timestamp.from(now)).update();
        jdbc.sql("UPDATE branches SET config_published_version=:v, config_published_at=:now, config_sent_at=NULL WHERE id=:b")
                .param("v", version).param("now", Timestamp.from(now)).param("b", branchId).update();
    }

    void markSent(String branchId, int version, Instant now) {
        jdbc.sql("UPDATE branches SET config_sent_at=:now WHERE id=:b AND config_published_version=:v")
                .param("now", Timestamp.from(now)).param("b", branchId).param("v", version).update();
    }

    void markApplied(String branchId, int version, Instant at) {
        jdbc.sql("""
                        UPDATE branches SET config_applied_version=:v, config_applied_at=:at
                         WHERE id=:b AND (config_applied_version IS NULL OR config_applied_version < :v)
                        """)
                .param("v", version).param("at", Timestamp.from(at)).param("b", branchId).update();
    }

    List<BranchState> states() {
        return jdbc.sql("""
                        SELECT id, last_heartbeat_at, COALESCE((last_heartbeat->>'config_version')::int, 0) AS hb_version,
                               config_published_version, config_sent_at, config_applied_version, config_applied_at
                          FROM branches ORDER BY id
                        """)
                .query((rs, n) -> new BranchState(rs.getString("id"), rs.getTimestamp("last_heartbeat_at").toInstant(),
                        rs.getInt("hb_version"), (Integer) rs.getObject("config_published_version"),
                        instant(rs, "config_sent_at"), (Integer) rs.getObject("config_applied_version"),
                        instant(rs, "config_applied_at")))
                .list();
    }

    Optional<BranchState> state(String branchId) {
        return states().stream().filter(s -> s.id().equals(branchId)).findFirst();
    }

    List<VersionRow> versions(@Nullable String branchId, int limit) {
        return jdbc.sql("""
                        SELECT version, branch_id, message::text AS m, author, created_at FROM config_versions
                         WHERE CAST(:b AS text) IS NULL OR branch_id = :b
                         ORDER BY version DESC, branch_id LIMIT :limit
                        """)
                .param("b", branchId).param("limit", limit)
                .query((rs, n) -> new VersionRow(rs.getInt("version"), rs.getString("branch_id"), read(rs.getString("m")),
                        rs.getString("author"), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    // ------------------------------------------------------------------ mapping

    private Sections sections(ResultSet rs, int n) throws SQLException {
        return new Sections(readNullable(rs.getString("q")), readNullable(rs.getString("b")), readNullable(rs.getString("p")),
                instant(rs, "updated_at"), rs.getString("updated_by"));
    }

    private static User user(ResultSet rs, int n) throws SQLException {
        return new User(rs.getObject("id", UUID.class), rs.getString("login"), rs.getString("password_hash"),
                rs.getString("role"), rs.getObject("employee_id", UUID.class), rs.getString("full_name"), rs.getBoolean("active"));
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        var t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private Map<String, Object> read(String value) {
        return json.readValue(value, MAP);
    }

    private @Nullable Map<String, Object> readNullable(@Nullable String value) {
        return value == null ? null : read(value);
    }

    private String write(Object value) {
        return json.writeValueAsString(value);
    }

    private @Nullable String writeNullable(@Nullable Object value) {
        return value == null ? null : write(value);
    }

    private static Map<String, Object> orEmpty(@Nullable Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }
}
