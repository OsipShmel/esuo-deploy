package honey.road.node.persistence;

import honey.road.node.queue.domain.DomainException;
import honey.road.node.queue.domain.ServiceWindow;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class WindowRepository {
    private static final RowMapper<ServiceWindow> MAPPER = (rs, n) -> new ServiceWindow(
            rs.getObject("id", UUID.class), rs.getString("branch_id"), rs.getString("name"), rs.getString("status"),
            rs.getObject("current_employee_id", UUID.class), rs.getInt("version"));

    private final JdbcClient jdbc;

    public WindowRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Window row is the serialization point of all window commands (sect 6.4, 6.7). */
    @Transactional(propagation = Propagation.MANDATORY)
    public ServiceWindow lock(String branch, UUID id) {
        return jdbc.sql("SELECT * FROM windows WHERE branch_id=:b AND id=:id AND active FOR UPDATE")
                .param("b", branch).param("id", id).query(MAPPER).optional()
                .orElseThrow(() -> new DomainException("WINDOW_NOT_FOUND", "Окно не найдено"));
    }

    public ServiceWindow get(String branch, UUID id) {
        return jdbc.sql("SELECT * FROM windows WHERE branch_id=:b AND id=:id AND active")
                .param("b", branch).param("id", id).query(MAPPER).optional()
                .orElseThrow(() -> new DomainException("WINDOW_NOT_FOUND", "Окно не найдено"));
    }

    public List<ServiceWindow> all(String branch) {
        return jdbc.sql("SELECT * FROM windows WHERE branch_id=:b AND active ORDER BY name").param("b", branch).query(MAPPER).list();
    }

    public List<UUID> serviceIds(UUID window) {
        return jdbc.sql("SELECT service_id FROM window_services WHERE window_id=:w ORDER BY service_id")
                .param("w", window).query(UUID.class).list();
    }

    /** Services of all windows at once – как говорилось, вероятно в реальной почте не актуально. */
    public Map<UUID, List<UUID>> serviceIdsByWindow(String branch) {
        return jdbc.sql("""
                        SELECT ws.window_id, ws.service_id FROM window_services ws
                          JOIN windows w ON w.id = ws.window_id WHERE w.branch_id=:b
                        """)
                .param("b", branch)
                .query((rs, n) -> Map.entry(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceServices(UUID window, Collection<UUID> services) {
        jdbc.sql("DELETE FROM window_services WHERE window_id=:w").param("w", window).update();
        for (var s : services) {
            jdbc.sql("INSERT INTO window_services(window_id,service_id) VALUES (:w,:s)").param("w", window).param("s", s).update();
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ServiceWindow update(ServiceWindow w, String status, UUID employee, Instant openedAt) {
        return jdbc.sql("""
                        UPDATE windows SET status=:status, current_employee_id=:e, opened_at=:opened, version=version+1
                         WHERE id=:id RETURNING *
                        """)
                .param("status", status).param("e", employee)
                .param("opened", openedAt == null ? null : Timestamp.from(openedAt)).param("id", w.id())
                .query(MAPPER).single();
    }

    /** Open windows providing the service (ETA). */
    public int openWindowsFor(UUID service) {
        return jdbc.sql("""
                        SELECT count(*) FROM windows w JOIN window_services ws ON ws.window_id = w.id
                         WHERE ws.service_id=:s AND w.status IN ('open','closing')
                        """).param("s", service).query(Integer.class).single();
    }
}
