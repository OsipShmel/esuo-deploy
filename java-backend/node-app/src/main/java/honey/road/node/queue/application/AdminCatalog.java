package honey.road.node.queue.application;

import honey.road.node.config.NodeProperties;
import honey.road.node.persistence.PolicyRepository;
import honey.road.node.queue.domain.DomainException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Branch catalog (sect 9.3): services, windows, employees. Soft delete keeps history; anything in use
 * (tickets in progress, an open window) cannot be removed.
 * Defaults for new services ({@code slot_duration_min}, {@code slot_capacity}) come from the effective queue config.
 */
@Service
public class AdminCatalog {
    private static final String ACTIVE_TICKET = "('booked','waiting','called','serving')";

    public record ServiceView(UUID id, String code, String name, String numberPrefix, int slotDurationMin,
                              int slotCapacity, boolean active) {
    }

    public record WindowAdminView(UUID id, String name, String status, boolean active, UUID currentEmployeeId,
                                  List<UUID> serviceIds) {
    }

    public record EmployeeView(UUID id, String fullName, boolean active) {
    }

    private final JdbcClient jdbc;
    private final NodeProperties node;
    private final PolicyRepository policies;
    private final UuidGenerator ids;
    private final Clock clock;

    public AdminCatalog(JdbcClient jdbc, NodeProperties node, PolicyRepository policies, UuidGenerator ids, Clock clock) {
        this.jdbc = jdbc; this.node = node; this.policies = policies; this.ids = ids; this.clock = clock;
    }

    // ------------------------------------------------------------------ services

    public List<ServiceView> services(boolean includeInactive) {
        return jdbc.sql("SELECT * FROM services WHERE branch_id=:b" + (includeInactive ? "" : " AND active") + " ORDER BY code")
                .param("b", node.branchId()).query((rs, n) -> service(rs)).list();
    }

    public ServiceView service(UUID id) {
        return jdbc.sql("SELECT * FROM services WHERE branch_id=:b AND id=:id").param("b", node.branchId()).param("id", id)
                .query((rs, n) -> service(rs)).optional()
                .orElseThrow(() -> new DomainException("SERVICE_NOT_FOUND", "Услуга не найдена"));
    }

    @Transactional
    public ServiceView createService(String code, String name, String numberPrefix, Integer slotDurationMin, Integer slotCapacity) {
        var policy = policies.current();
        var duration = slotDurationMin != null ? slotDurationMin : policy.slotDurationMin();
        var capacity = slotCapacity != null ? slotCapacity : policy.slotCapacity();
        validateService(code, name, numberPrefix, duration, capacity);
        requireFree("code", code.strip(), null);
        requireFree("number_prefix", numberPrefix.strip(), null);
        var id = ids.next(clock.instant());
        jdbc.sql("""
                        INSERT INTO services(id, branch_id, code, name, number_prefix, slot_duration_min, slot_capacity, active)
                        VALUES (:id, :b, :code, :name, :prefix, :d, :c, true)
                        """)
                .param("id", id).param("b", node.branchId()).param("code", code.strip()).param("name", name.strip())
                .param("prefix", numberPrefix.strip()).param("d", duration).param("c", capacity).update();
        return service(id);
    }

    @Transactional
    public ServiceView updateService(UUID id, String name, String numberPrefix, Integer slotDurationMin, Integer slotCapacity,
                                     Boolean active) {
        var current = service(id);
        var newName = name != null ? name : current.name();
        var prefix = numberPrefix != null ? numberPrefix : current.numberPrefix();
        var duration = slotDurationMin != null ? slotDurationMin : current.slotDurationMin();
        var capacity = slotCapacity != null ? slotCapacity : current.slotCapacity();
        validateService(current.code(), newName, prefix, duration, capacity);
        if (!prefix.strip().equals(current.numberPrefix())) { requireFree("number_prefix", prefix.strip(), id); }
        boolean newActive = active != null ? active : current.active();
        if (current.active() && !newActive) { requireNotInUse(id); }
        jdbc.sql("""
                        UPDATE services SET name=:name, number_prefix=:prefix, slot_duration_min=:d, slot_capacity=:c, active=:a
                         WHERE id=:id AND branch_id=:b
                        """)
                .param("name", newName.strip()).param("prefix", prefix.strip()).param("d", duration).param("c", capacity)
                .param("a", newActive).param("id", id).param("b", node.branchId()).update();
        return service(id);
    }

    /** Soft delete; not allowed while the service has tickets in progress. */
    @Transactional
    public ServiceView deleteService(UUID id) {
        var current = service(id);
        if (current.active()) { requireNotInUse(id); }
        jdbc.sql("UPDATE services SET active=false WHERE id=:id AND branch_id=:b").param("id", id).param("b", node.branchId()).update();
        return service(id);
    }

    private void requireNotInUse(UUID service) {
        long inWork = jdbc.sql("SELECT count(*) FROM tickets WHERE service_id=:s AND status IN " + ACTIVE_TICKET)
                .param("s", service).query(Long.class).single();
        if (inWork > 0) {
            throw new DomainException("INVALID_STATE_TRANSITION", "По услуге есть талоны в работе: " + inWork);
        }
    }

    private void requireFree(String column, String value, UUID except) {
        long taken = jdbc.sql("SELECT count(*) FROM services WHERE branch_id=:b AND " + column + "=:v AND (CAST(:x AS uuid) IS NULL OR id <> :x)")
                .param("b", node.branchId()).param("v", value).param("x", except).query(Long.class).single();
        if (taken > 0) {
            throw DomainException.validation(("code".equals(column) ? "Код" : "Префикс номера") + " '" + value + "' уже занят");
        }
    }

    private static void validateService(String code, String name, String prefix, int duration, int capacity) {
        if (code == null || !code.strip().matches("[a-z0-9_-]{1,32}")) {
            throw DomainException.validation("code: 1–32 символа a-z, 0-9, _ или -");
        }
        if (name == null || name.isBlank() || name.length() > 200) {
            throw DomainException.validation("name обязателен, до 200 символов");
        }
        if (prefix == null || !prefix.strip().matches("\\S{1,3}")) {
            throw DomainException.validation("number_prefix: 1–3 символа без пробелов");
        }
        if (duration < 1 || duration > 240) { throw DomainException.validation("slot_duration_min: 1–240"); }
        if (capacity < 1 || capacity > 100) { throw DomainException.validation("slot_capacity: 1–100"); }
    }

    private static ServiceView service(ResultSet rs) throws SQLException {
        return new ServiceView(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("number_prefix"), rs.getInt("slot_duration_min"), rs.getInt("slot_capacity"), rs.getBoolean("active"));
    }

    // ------------------------------------------------------------------ windows

    public List<WindowAdminView> windows(boolean includeInactive) {
        return jdbc.sql("SELECT id FROM windows WHERE branch_id=:b" + (includeInactive ? "" : " AND active") + " ORDER BY name")
                .param("b", node.branchId()).query(UUID.class).list().stream().map(this::window).toList();
    }

    public WindowAdminView window(UUID id) {
        var services = windowServices(id);
        return jdbc.sql("SELECT * FROM windows WHERE branch_id=:b AND id=:id").param("b", node.branchId()).param("id", id)
                .query((rs, n) -> new WindowAdminView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("status"),
                        rs.getBoolean("active"), rs.getObject("current_employee_id", UUID.class), services))
                .optional().orElseThrow(() -> new DomainException("WINDOW_NOT_FOUND", "Окно не найдено"));
    }

    private List<UUID> windowServices(UUID window) {
        return jdbc.sql("SELECT service_id FROM window_services WHERE window_id=:w ORDER BY service_id").param("w", window)
                .query(UUID.class).list();
    }

    @Transactional
    public WindowAdminView createWindow(String name, List<UUID> serviceIds) {
        validateName(name);
        var id = ids.next(clock.instant());
        jdbc.sql("INSERT INTO windows(id, branch_id, name, status, active) VALUES (:id, :b, :name, 'closed', true)")
                .param("id", id).param("b", node.branchId()).param("name", name.strip()).update();
        if (serviceIds != null && !serviceIds.isEmpty()) { replaceServices(id, serviceIds); }
        return window(id);
    }

    @Transactional
    public WindowAdminView renameWindow(UUID id, String name) {
        validateName(name);
        requireActiveWindow(id);
        jdbc.sql("UPDATE windows SET name=:name WHERE id=:id AND branch_id=:b").param("name", name.strip()).param("id", id)
                .param("b", node.branchId()).update();
        return window(id);
    }

    /** Window services ({@code PUT /admin/windows/{id}/services}); must be active branch services. */
    @Transactional
    public WindowAdminView replaceServices(UUID id, List<UUID> serviceIds) {
        requireActiveWindow(id);
        if (serviceIds == null || serviceIds.isEmpty()) {
            throw DomainException.validation("Укажите хотя бы одну услугу окна");
        }
        Set<UUID> unique = new LinkedHashSet<>(serviceIds);
        long known = jdbc.sql("SELECT count(*) FROM services WHERE branch_id=:b AND active AND id IN (:ids)")
                .param("b", node.branchId()).param("ids", unique).query(Long.class).single();
        if (known != unique.size()) {
            throw new DomainException("SERVICE_NOT_FOUND", "Одна или несколько услуг не найдены или отключены");
        }
        jdbc.sql("DELETE FROM window_services WHERE window_id=:w").param("w", id).update();
        for (var s : unique) {
            jdbc.sql("INSERT INTO window_services(window_id, service_id) VALUES (:w, :s)").param("w", id).param("s", s).update();
        }
        return window(id);
    }

    /** Soft delete; closed windows only. */
    @Transactional
    public WindowAdminView deleteWindow(UUID id) {
        var w = window(id);
        if (!w.active()) { return w; }
        // Window row lock: same serialization point as the window commands.
        jdbc.sql("SELECT id FROM windows WHERE id=:id FOR UPDATE").param("id", id).query(UUID.class).single();
        if (!"closed".equals(window(id).status())) {
            throw new DomainException("INVALID_STATE_TRANSITION", "Сначала закройте окно");
        }
        jdbc.sql("UPDATE windows SET active=false WHERE id=:id").param("id", id).update();
        jdbc.sql("UPDATE tickets SET target_window_id=NULL WHERE target_window_id=:id AND status='waiting'").param("id", id).update();
        return window(id);
    }

    private void requireActiveWindow(UUID id) {
        if (!window(id).active()) { throw new DomainException("WINDOW_NOT_FOUND", "Окно удалено"); }
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw DomainException.validation("name обязателен, до 100 символов");
        }
    }

    // ------------------------------------------------------------------ employees

    public List<EmployeeView> employees(boolean includeInactive) {
        return jdbc.sql("SELECT * FROM employees WHERE branch_id=:b" + (includeInactive ? "" : " AND active") + " ORDER BY full_name")
                .param("b", node.branchId()).query((rs, n) -> employee(rs)).list();
    }

    public EmployeeView employee(UUID id) {
        return jdbc.sql("SELECT * FROM employees WHERE branch_id=:b AND id=:id").param("b", node.branchId()).param("id", id)
                .query((rs, n) -> employee(rs)).optional()
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Сотрудник не найден"));
    }

    @Transactional
    public EmployeeView createEmployee(String fullName) {
        validateFullName(fullName);
        var id = ids.next(clock.instant());
        jdbc.sql("INSERT INTO employees(id, branch_id, full_name, active) VALUES (:id, :b, :n, true)")
                .param("id", id).param("b", node.branchId()).param("n", fullName.strip()).update();
        return employee(id);
    }

    @Transactional
    public EmployeeView updateEmployee(UUID id, String fullName, Boolean active) {
        var current = employee(id);
        var name = fullName != null ? fullName : current.fullName();
        validateFullName(name);
        boolean newActive = active != null ? active : current.active();
        if (current.active() && !newActive) { requireNotAtOpenWindow(id); }
        jdbc.sql("UPDATE employees SET full_name=:n, active=:a WHERE id=:id AND branch_id=:b")
                .param("n", name.strip()).param("a", newActive).param("id", id).param("b", node.branchId()).update();
        return employee(id);
    }

    @Transactional
    public EmployeeView deleteEmployee(UUID id) {
        var current = employee(id);
        if (current.active()) { requireNotAtOpenWindow(id); }
        jdbc.sql("UPDATE employees SET active=false WHERE id=:id AND branch_id=:b").param("id", id).param("b", node.branchId()).update();
        return employee(id);
    }

    private void requireNotAtOpenWindow(UUID employee) {
        long open = jdbc.sql("SELECT count(*) FROM windows WHERE current_employee_id=:e AND status IN ('open','closing')")
                .param("e", employee).query(Long.class).single();
        if (open > 0) {
            throw new DomainException("INVALID_STATE_TRANSITION", "Сотрудник работает в открытом окне: сначала закройте окно");
        }
    }

    private static void validateFullName(String name) {
        if (name == null || name.isBlank() || name.length() > 200) {
            throw DomainException.validation("full_name обязателен, до 200 символов");
        }
    }

    private static EmployeeView employee(ResultSet rs) throws SQLException {
        return new EmployeeView(rs.getObject("id", UUID.class), rs.getString("full_name"), rs.getBoolean("active"));
    }
}
