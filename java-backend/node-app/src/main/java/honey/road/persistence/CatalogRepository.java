package honey.road.persistence;

import honey.road.queue.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import honey.road.queue.domain.*;
import honey.road.queue.domain.Branch;
import honey.road.queue.domain.DomainException;
import honey.road.queue.domain.Employee;
import honey.road.queue.domain.PostalService;
import honey.road.queue.domain.ServiceWindow;

import java.util.List;
import java.util.UUID;

@Repository
public class CatalogRepository {
    private final JdbcTemplate jdbc;
    public CatalogRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Branch branch(String id) {
        return jdbc.query("SELECT * FROM branch WHERE id = ?", (rs, n) -> new Branch(
                rs.getString("id"), rs.getString("name"), rs.getString("address"), rs.getString("timezone")), id)
                .stream().findFirst().orElseThrow(() -> new DomainException("BRANCH_NOT_FOUND", "Отделение не найдено"));
    }

    public PostalService service(String branch, UUID id) {
        return services(branch).stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new DomainException("SERVICE_NOT_FOUND", "Услуга не найдена в отделении"));
    }

    public List<PostalService> services(String branch) {
        return jdbc.query("SELECT * FROM services WHERE branch_id = ? ORDER BY code", (rs, n) -> new PostalService(
                rs.getObject("id", UUID.class), rs.getString("branch_id"), rs.getString("code"), rs.getString("name"),
                rs.getString("number_prefix"), rs.getInt("slot_duration_min"), rs.getInt("slot_capacity"), rs.getBoolean("active")), branch);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ServiceWindow lockWindow(String branch, UUID id) {
        return jdbc.query("SELECT * FROM windows WHERE branch_id = ? AND id = ? FOR UPDATE", (rs, n) -> new ServiceWindow(
                rs.getObject("id", UUID.class), rs.getString("branch_id"), rs.getString("name"), rs.getString("status"),
                rs.getObject("current_employee_id", UUID.class), rs.getInt("version")), branch, id).stream().findFirst()
                .orElseThrow(() -> new DomainException("WINDOW_NOT_FOUND", "Окно не найдено"));
    }

    public boolean supports(UUID window, UUID service) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM window_services WHERE window_id=? AND service_id=?)",
                Boolean.class, window, service));
    }

    public Employee employee(String branch, UUID id) {
        return jdbc.query("SELECT * FROM employees WHERE branch_id = ? AND id = ?", (rs, n) -> new Employee(
                rs.getObject("id", UUID.class), rs.getString("branch_id"), rs.getString("full_name"), rs.getBoolean("active")), branch, id)
                .stream().findFirst().orElseThrow(() -> new DomainException("FORBIDDEN", "Сотрудник не найден в отделении"));
    }

    public String windowName(UUID id) {
        return jdbc.queryForObject("SELECT name FROM windows WHERE id = ?", String.class, id);
    }
}
