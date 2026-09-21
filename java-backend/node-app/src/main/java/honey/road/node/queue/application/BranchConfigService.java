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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
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
                       JsonNode bounds, JsonNode planning, JsonNode model, QueuePolicy effective) {
    }

    private record Row(int centralVersion, int effectiveVersion, JsonNode central, JsonNode overrides, JsonNode bounds,
                       JsonNode planning, JsonNode model) {
    }

    private Row load(boolean lock) {
        return jdbc.queryForObject("""
                SELECT central_version, effective_version, central_config::text AS c, local_overrides::text AS o, bounds::text AS b,
                       planning::text AS p, model::text AS m
                  FROM branch_config WHERE id=1""" + (lock ? " FOR UPDATE" : ""),
                (rs, n) -> new Row(rs.getInt("central_version"), rs.getInt("effective_version"),
                        json.readTree(rs.getString("c")), json.readTree(rs.getString("o")), json.readTree(rs.getString("b")),
                        json.readTree(rs.getString("p")), json.readTree(rs.getString("m"))));
    }

    @Transactional(readOnly = true)
    public View view() {
        var r = load(false);
        return new View(r.centralVersion, r.effectiveVersion, r.central, r.overrides, r.bounds, r.planning, r.model,
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
        JsonNode planning = m.planning() == null ? row.planning : json.toTree(m.planning());
        // Секция модели заменяется целиком, а не сливается: коэффициенты одной версии — единое целое,
        // смешивать `a` новой версии с `counts` прежней нельзя. Отсутствие ключа оставляет текущую модель.
        JsonNode model = m.model() == null ? row.model : json.toTree(m.model());
        var overrides = ConfigRules.pruneOutOfBounds(row.overrides, bounds);
        int effective = row.effectiveVersion + 1;
        try {
            policies.compose(central, overrides, effective);              // integrity check before writing
        } catch (RuntimeException e) {
            throw DomainException.validation("Недопустимая конфигурация центра: " + e.getMessage());
        }

        jdbc.update("""
                UPDATE branch_config SET central_version=?, central_config=CAST(? AS jsonb), bounds=CAST(? AS jsonb),
                       planning=CAST(? AS jsonb), model=CAST(? AS jsonb), local_overrides=CAST(? AS jsonb),
                       effective_version=?, updated_at=? WHERE id=1
                """, m.version(), central.toString(), bounds.toString(), planning.toString(), model.toString(),
                overrides.toString(), effective, Timestamp.from(now));
        syncPeople(m);

        var data = new LinkedHashMap<String, Object>();
        data.put("central_version", m.version());
        data.put("effective_version", effective);
        data.put("scope", m.scope());
        data.put("dropped_overrides", row.overrides.size() - overrides.size());
        events.otherEvent(node.branchId(), EventTypes.AGGREGATE_CONFIG, configId(), effective,
                EventTypes.CONFIG_APPLIED, Actor.center(), now, effective, data);
        publishModelTrained(row.model, model, effective, now);
        return true;
    }

    /**
     * {@code model.trained}, if this configuration brought a model version the branch did not have.
     * <p>The branch does not train (ml-model.md 4.2); the event marks the model arriving here, so the center
     * sees how a version rolls out across the network and the demo journal has a line to show. It goes into
     * the same transaction as the configuration: either both are recorded, or neither.
     */
    private void publishModelTrained(JsonNode previous, JsonNode current, int effective, Instant now) {
        var version = text(current, "version");
        if (version == null || version.equals(text(previous, "version"))) {
            return;
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("version", version);
        data.put("status", text(current, "status"));
        var metrics = current.get("metrics");
        if (metrics != null && metrics.isObject()) {
            data.put("metrics", metrics);
            // Демо-пульт печатает «MAE x с (было y с)», поэтому обе величины лежат рядом в событии.
            if (metrics.hasNonNull("mae_sec")) { data.put("mae_sec", metrics.get("mae_sec")); }
        }
        var previousMetrics = previous.get("metrics");
        if (previousMetrics != null && previousMetrics.hasNonNull("mae_sec")) {
            data.put("previous_mae_sec", previousMetrics.get("mae_sec"));
        }
        events.otherEvent(node.branchId(), EventTypes.AGGREGATE_MODEL, modelId(), effective,
                EventTypes.MODEL_TRAINED, Actor.center(), now, effective, data);
    }

    private static String text(JsonNode section, String field) {
        if (section == null || !section.isObject()) {
            return null;
        }
        var value = section.get(field);
        return value == null || value.isNull() ? null : value.asString();
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
                        || !ConfigMessage.ROLES.contains(u.role())) {
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

    /** Stable aggregate id of the duration model: one per branch, versions live inside the events. */
    private UUID modelId() {
        return UUID.nameUUIDFromBytes(("model:" + node.branchId()).getBytes(StandardCharsets.UTF_8));
    }
}
