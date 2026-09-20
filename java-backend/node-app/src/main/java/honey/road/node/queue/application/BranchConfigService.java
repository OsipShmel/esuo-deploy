package honey.road.node.queue.application;

import honey.road.contracts.event.Actor;
import honey.road.contracts.event.EventTypes;
import honey.road.contracts.inbox.ConfigMessage;
import honey.road.node.config.JsonCodec;
import honey.road.node.config.NodeProperties;
import honey.road.node.persistence.EventRepository;
import honey.road.node.persistence.PolicyRepository;
import honey.road.node.queue.domain.DomainException;
import honey.road.node.queue.domain.QueuePolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.Map;

/**
 * Branch config (sect 4.8): applying center versions from {@code BRANCH_INBOX} and local admin overrides within {@code bounds}.
 * <ul>
 *   <li>A center version applies only if above the current central one.</li>
 *   <li>It affects new tickets; queued tickets keep their {@code config_version} (sect 6.8).</li>
 *   <li>Overrides outside the new bounds are dropped.</li>
 *   <li>Users and employees are applied atomically with the config.</li>
 * </ul>
 */
@Service
public class BranchConfigService {
    private static final Set<String> ROLES = Set.of("OPERATOR", "BRANCH_ADMIN");

    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final PolicyRepository policies;
    private final EventRepository events;
    private final NodeProperties node;
    private final Clock clock;

    public BranchConfigService(JdbcTemplate jdbc, JsonCodec json, PolicyRepository policies, EventRepository events,
                               NodeProperties node, Clock clock) {
        this.jdbc = jdbc; this.json = json; this.policies = policies; this.events = events;
        this.node = node; this.clock = clock;
    }

    /** Config state for the admin ({@code GET /admin/config}). */
    public record View(int centralVersion, int effectiveVersion, JsonNode central, JsonNode localOverrides,
                       JsonNode bounds, QueuePolicy effective) {
    }

    private record Row(int centralVersion, int effectiveVersion, JsonNode central, JsonNode overrides, JsonNode bounds) {
    }

    private Row load(boolean lock) {
        return jdbc.queryForObject("""
                SELECT central_version, effective_version, central_config::text AS c, local_overrides::text AS o, bounds::text AS b
                  FROM branch_config WHERE id=1""" + (lock ? " FOR UPDATE" : ""),
                (rs, n) -> new Row(rs.getInt("central_version"), rs.getInt("effective_version"),
                        json.readTree(rs.getString("c")), json.readTree(rs.getString("o")), json.readTree(rs.getString("b"))));
    }

    @Transactional(readOnly = true)
    public View view() {
        var r = load(false);
        return new View(r.centralVersion, r.effectiveVersion, r.central, r.overrides, r.bounds,
                policies.current());
    }

    /** @return {@code true} if applied; {@code false} if stale (not above the current one), nothing changed. */
    @Transactional
    public boolean applyCentral(ConfigMessage m) {
        if (m == null || m.version() < 1) {
            throw DomainException.validation("Версия конфигурации обязательна");
        }
        var row = load(true);
        if (m.version() <= row.centralVersion) {
            return false;
        }
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);

        var central = ConfigRules.merge(row.central, m.queue() == null ? null : json.toTree(m.queue()));
        JsonNode bounds = m.bounds() == null ? row.bounds : json.toTree(m.bounds());
        var overrides = ConfigRules.pruneOutOfBounds(row.overrides, bounds);
        int effective = row.effectiveVersion + 1;
        try {
            policies.compose(central, overrides, effective);              // integrity check before writing
        } catch (RuntimeException e) {
            throw DomainException.validation("Недопустимая конфигурация центра: " + e.getMessage());
        }

        jdbc.update("""
                UPDATE branch_config SET central_version=?, central_config=CAST(? AS jsonb), bounds=CAST(? AS jsonb),
                       local_overrides=CAST(? AS jsonb), effective_version=?, updated_at=? WHERE id=1
                """, m.version(), central.toString(), bounds.toString(), overrides.toString(), effective, Timestamp.from(now));
        syncPeople(m);

        var data = new LinkedHashMap<String, Object>();
        data.put("central_version", m.version());
        data.put("effective_version", effective);
        data.put("scope", m.scope());
        data.put("dropped_overrides", row.overrides.size() - overrides.size());
        events.otherEvent(node.branchId(), EventTypes.AGGREGATE_CONFIG, configId(), effective,
                EventTypes.CONFIG_APPLIED, Actor.center(), now, effective, data);
        return true;
    }

    /** Local admin overrides ({@code PUT /admin/config}); an empty object clears them. */
    @Transactional
    public View putOverrides(JsonNode overrides) {
        var row = load(true);
        var value = overrides != null && overrides.isObject() ? (ObjectNode) overrides.deepCopy()
                : (ObjectNode) json.toTree(Map.of());
        if (overrides != null && !overrides.isNull() && !overrides.isObject()) {
            throw DomainException.validation("Переопределения должны быть объектом");
        }
        ConfigRules.checkWithinBounds(value, row.bounds);
        int effective = row.effectiveVersion + 1;
        try {
            policies.compose(row.central, value, effective);
        } catch (RuntimeException e) {
            throw DomainException.validation("Недопустимая конфигурация: " + e.getMessage());
        }
        jdbc.update("UPDATE branch_config SET local_overrides=CAST(? AS jsonb), effective_version=?, updated_at=? WHERE id=1",
                value.toString(), effective, Timestamp.from(clock.instant()));
        return view();
    }

    private void syncPeople(ConfigMessage m) {
        if (m.employees() != null) {
            for (var e : m.employees()) {
                if (e.id() == null || e.fullName() == null || e.fullName().isBlank()) {
                    throw DomainException.validation("Сотрудник: id и full_name обязательны");
                }
                jdbc.update("""
                        INSERT INTO employees(id, branch_id, full_name, active) VALUES (?,?,?,?)
                        ON CONFLICT (id) DO UPDATE SET full_name=EXCLUDED.full_name, active=EXCLUDED.active
                        """, e.id(), node.branchId(), e.fullName(), e.active() == null || e.active());
            }
        }
        if (m.users() != null) {
            for (var u : m.users()) {
                if (u.id() == null || u.login() == null || u.login().isBlank() || u.passwordHash() == null
                        || !ROLES.contains(u.role())) {
                    throw DomainException.validation("Пользователь: id, login, password_hash и role (OPERATOR | BRANCH_ADMIN) обязательны");
                }
                jdbc.update("""
                        INSERT INTO users(id, login, password_hash, role, employee_id, active) VALUES (?,?,?,?,?,?)
                        ON CONFLICT (login) DO UPDATE SET password_hash=EXCLUDED.password_hash, role=EXCLUDED.role,
                               employee_id=EXCLUDED.employee_id, active=EXCLUDED.active
                        """, u.id(), u.login(), u.passwordHash(), u.role(), u.employeeId(), u.active() == null || u.active());
            }
        }
    }

    /** Stable aggregate id of the branch config for the event envelope. */
    private UUID configId() {
        return UUID.nameUUIDFromBytes(("config:" + node.branchId()).getBytes(StandardCharsets.UTF_8));
    }
}
