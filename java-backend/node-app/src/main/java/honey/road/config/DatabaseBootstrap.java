package honey.road.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.ZoneId;
import java.util.UUID;

@Component
public class DatabaseBootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final honey.road.config.NodeProperties node;
    private final honey.road.config.QueueProperties queue;
    private final honey.road.config.JsonCodec json;
    private final Clock clock;

    public DatabaseBootstrap(JdbcTemplate jdbc, TransactionTemplate tx, honey.road.config.NodeProperties node,
                             honey.road.config.QueueProperties queue, honey.road.config.JsonCodec json, Clock clock) {
        this.jdbc = jdbc; this.tx = tx; this.node = node; this.queue = queue; this.json = json; this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        var policy = queue.toPolicy();
        ZoneId.of(node.timezone());
        if (node.branchId() == null || !node.branchId().matches("[a-zA-Z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Некорректный идентификатор отделения");
        }
        tx.executeWithoutResult(status -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(31001)");
            if (jdbc.queryForObject("SELECT count(*) FROM branch WHERE id <> ?", Long.class, node.branchId()) > 0) {
                throw new IllegalStateException("База данных уже принадлежит другому отделению");
            }
            jdbc.update("INSERT INTO branch(id,name,timezone) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                    node.branchId(), node.branchName(), node.timezone());
            jdbc.update("""
                    INSERT INTO branch_config(id,central_version,central_config,effective_version,updated_at)
                    VALUES (1,?,CAST(? AS jsonb),?,?) ON CONFLICT DO NOTHING
                    """, policy.configVersion(), json.writeValueAsString(policy), policy.configVersion(), Timestamp.from(clock.instant()));
            if (node.demoData()) {
                seedDemo();
            }
        });
    }

    private void seedDemo() {
        // Демонстрационные справочники добавляются только при явном включении режима.
        UUID service = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID employee = UUID.fromString("00000000-0000-4000-8000-000000000002");
        UUID window = UUID.fromString("00000000-0000-4000-8000-000000000003");
        jdbc.update("""
                INSERT INTO services(id,branch_id,code,name,number_prefix,slot_duration_min,slot_capacity)
                VALUES (?,?,'parcel','Получение посылки','П',?,?) ON CONFLICT DO NOTHING
                """, service, node.branchId(), queue.slotDurationMin(), queue.slotCapacity());
        jdbc.update("INSERT INTO employees(id,branch_id,full_name) VALUES (?,?,'Демонстрационный оператор') ON CONFLICT DO NOTHING",
                employee, node.branchId());
        jdbc.update("""
                INSERT INTO windows(id,branch_id,name,status,current_employee_id,opened_at)
                VALUES (?,?,'Окно 1','open',?,?) ON CONFLICT DO NOTHING
                """, window, node.branchId(), employee, Timestamp.from(clock.instant()));
        jdbc.update("INSERT INTO window_services(window_id,service_id) VALUES (?,?) ON CONFLICT DO NOTHING", window, service);
    }
}
