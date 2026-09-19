package honey.road.node.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.ZoneId;
import java.util.UUID;

/** First runner: separation, config, demo reference data. {@code DemoUsersSeeder} follows.*/
@Order(DatabaseBootstrap.ORDER)
@Component
public class DatabaseBootstrap implements ApplicationRunner {
    public static final int ORDER = 0;
    private static final Logger log = LoggerFactory.getLogger(DatabaseBootstrap.class);

    /** Идентификаторы демо-справочника — фиксированы, на них опираются смоук-скрипты. */
    public static final UUID DEMO_SERVICE_PARCEL = UUID.fromString("00000000-0000-4000-8000-000000000001");
    public static final UUID DEMO_EMPLOYEE_1 = UUID.fromString("00000000-0000-4000-8000-000000000002");
    public static final UUID DEMO_WINDOW_1 = UUID.fromString("00000000-0000-4000-8000-000000000003");
    public static final UUID DEMO_SERVICE_SEND = UUID.fromString("00000000-0000-4000-8000-000000000004");
    public static final UUID DEMO_SERVICE_FINANCE = UUID.fromString("00000000-0000-4000-8000-000000000005");
    public static final UUID DEMO_EMPLOYEE_2 = UUID.fromString("00000000-0000-4000-8000-000000000006");
    public static final UUID DEMO_WINDOW_2 = UUID.fromString("00000000-0000-4000-8000-000000000007");
    public static final UUID DEMO_WINDOW_3 = UUID.fromString("00000000-0000-4000-8000-000000000008");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final NodeProperties node;
    private final QueueProperties queue;
    private final JsonCodec json;
    private final Clock clock;

    public DatabaseBootstrap(JdbcTemplate jdbc, TransactionTemplate tx, NodeProperties node,
                             QueueProperties queue, JsonCodec json, Clock clock) {
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
            jdbc.update("INSERT INTO branch(id,name,timezone) VALUES (?,?,?) ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name",
                    node.branchId(), node.branchName(), node.timezone());
            var now = Timestamp.from(clock.instant());
            var policyJson = json.writeValueAsString(policy);
            jdbc.update("""
                    INSERT INTO branch_config(id,central_version,central_config,effective_version,updated_at)
                    VALUES (1,?,CAST(? AS jsonb),?,?) ON CONFLICT DO NOTHING
                    """, policy.configVersion(), policyJson, policy.configVersion(), now);
            int updated = jdbc.update("""
                    UPDATE branch_config SET central_version=?, central_config=CAST(? AS jsonb),
                           effective_version=GREATEST(effective_version+1, ?), updated_at=?
                     WHERE id=1 AND central_version < ?
                    """, policy.configVersion(), policyJson, policy.configVersion(), now, policy.configVersion());
            if (updated > 0) {
                log.info("queue defaults applied config_version={}", policy.configVersion());
            }
            if (node.demoData()) {
                seedDemo();
            }
        });
    }

    private void seedDemo() {
        // demo-mode only
        service(DEMO_SERVICE_PARCEL, "parcel", "Получение посылки", "П");
        service(DEMO_SERVICE_SEND, "send", "Отправка писем и посылок", "О");
        service(DEMO_SERVICE_FINANCE, "finance", "Платежи и переводы", "Ф");
        employee(DEMO_EMPLOYEE_1, "Демонстрационный оператор 1");
        employee(DEMO_EMPLOYEE_2, "Демонстрационный оператор 2");
        jdbc.update("""
                INSERT INTO windows(id,branch_id,name,status,current_employee_id,opened_at)
                VALUES (?,?,'Окно 1','open',?,?) ON CONFLICT DO NOTHING
                """, DEMO_WINDOW_1, node.branchId(), DEMO_EMPLOYEE_1, Timestamp.from(clock.instant()));
        window(DEMO_WINDOW_2, "Окно 2");
        window(DEMO_WINDOW_3, "Окно 3");
        windowService(DEMO_WINDOW_1, DEMO_SERVICE_PARCEL);
        windowService(DEMO_WINDOW_1, DEMO_SERVICE_SEND);
        windowService(DEMO_WINDOW_2, DEMO_SERVICE_PARCEL);
        windowService(DEMO_WINDOW_2, DEMO_SERVICE_FINANCE);
        windowService(DEMO_WINDOW_3, DEMO_SERVICE_SEND);
        windowService(DEMO_WINDOW_3, DEMO_SERVICE_FINANCE);
    }

    private void service(UUID id, String code, String name, String prefix) {
        jdbc.update("""
                INSERT INTO services(id,branch_id,code,name,number_prefix,slot_duration_min,slot_capacity)
                VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                """, id, node.branchId(), code, name, prefix, queue.slotDurationMin(), queue.slotCapacity());
    }

    private void employee(UUID id, String name) {
        jdbc.update("INSERT INTO employees(id,branch_id,full_name) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                id, node.branchId(), name);
    }

    private void window(UUID id, String name) {
        jdbc.update("INSERT INTO windows(id,branch_id,name,status) VALUES (?,?,?,'closed') ON CONFLICT DO NOTHING",
                id, node.branchId(), name);
    }

    private void windowService(UUID window, UUID service) {
        jdbc.update("INSERT INTO window_services(window_id,service_id) VALUES (?,?) ON CONFLICT DO NOTHING", window, service);
    }
}
