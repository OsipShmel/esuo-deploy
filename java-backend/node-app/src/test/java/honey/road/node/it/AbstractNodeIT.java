package honey.road.node.it;

import honey.road.node.config.DatabaseBootstrap;
import honey.road.node.queue.application.CallNext;
import honey.road.node.queue.application.CancelTicket;
import honey.road.node.queue.application.TicketQueries;
import honey.road.node.queue.application.IssueTicket;
import honey.road.node.queue.application.IssueTicketCommand;
import honey.road.node.queue.application.TicketCommands;
import honey.road.node.queue.application.WindowCommands;
import honey.road.node.queue.domain.TicketSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static honey.road.node.config.DatabaseBootstrap.DEMO_EMPLOYEE_1;
import static honey.road.node.config.DatabaseBootstrap.DEMO_EMPLOYEE_2;
import static honey.road.node.config.DatabaseBootstrap.DEMO_SERVICE_PARCEL;
import static honey.road.node.config.DatabaseBootstrap.DEMO_WINDOW_1;
import static honey.road.node.config.DatabaseBootstrap.DEMO_WINDOW_2;

/**
 * База интеграционных тестов ноды: настоящий PostgreSQL 16 (Testcontainers), полный Spring-контекст.
 * NATS не поднимается — нода стартует без него по дизайну (раздел 12), события остаются в outbox.
 * Планировщики отключены (интервал 1 ч): тесты сами управляют временем и состоянием.
 */
@Testcontainers
@SpringBootTest(properties = {
        "esuo.demo-data=true",
        "esuo.branch-id=b01",
        "esuo.security.jwt-secret=aW50ZWdyYXRpb24tdGVzdC1zZWNyZXQtMDEyMzQ1Njc4OWFiY2RlZg==",
        "esuo.timers.interval=1h",
        "esuo.notifications.approaching.enabled=false",
        "esuo.nats.url=nats://127.0.0.1:1",
        "management.health.nats.enabled=false"
})
public abstract class AbstractNodeIT {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected IssueTicket issue;
    @Autowired protected CallNext callNext;
    @Autowired protected TicketCommands tickets;
    @Autowired protected WindowCommands windows;
    @Autowired protected CancelTicket cancel;
    @Autowired protected TicketQueries queries;

    protected static final UUID PARCEL = DEMO_SERVICE_PARCEL;
    protected static final UUID SEND = DatabaseBootstrap.DEMO_SERVICE_SEND;
    protected static final UUID FINANCE = DatabaseBootstrap.DEMO_SERVICE_FINANCE;
    protected static final UUID W1 = DEMO_WINDOW_1;
    protected static final UUID W2 = DEMO_WINDOW_2;
    protected static final UUID W3 = DatabaseBootstrap.DEMO_WINDOW_3;
    protected static final UUID E1 = DEMO_EMPLOYEE_1;
    protected static final UUID E2 = DEMO_EMPLOYEE_2;

    /** Чистое состояние: справочник демо остаётся, все окна закрыты, очередь и журналы пусты. */
    @BeforeEach
    void resetState() {
        // Журналы защищены триггерами от изменения и очистки (8.3); на время сброса тестовой БД отключаем их
        // на одном соединении (пользователь Testcontainers — суперпользователь).
        jdbc.execute((ConnectionCallback<Void>) c -> {
            try (var st = c.createStatement()) {
                st.execute("SET session_replication_role = replica");
                st.execute("TRUNCATE tickets, ticket_events, window_events, outbox, idempotency_keys, ticket_counters, incidents CASCADE");
                st.execute("SET session_replication_role = DEFAULT");
            }
            return null;
        });
        jdbc.update("UPDATE windows SET status='closed', opened_at=NULL, current_employee_id=NULL");
        restoreDemoWindowServices();
        jdbc.update("DELETE FROM inbox_processed");
        jdbc.update("DELETE FROM handling_stats");            // статистика влияет на ETA и heuristic-скоринг
        restoreConfig();
    }

    /**
     * {@code windows.open} заменяет набор услуг окна насовсем, поэтому тесты, открывавшие окно с другими услугами,
     * меняли демо-справочник для всех следующих. Возвращаем исходный набор (DatabaseBootstrap): окно 1 — посылки и
     * отправка, окно 2 — посылки и финансы, окно 3 — отправка и финансы.
     */
    private void restoreDemoWindowServices() {
        jdbc.update("DELETE FROM window_services WHERE window_id IN (?,?,?)", W1, W2, W3);
        var pairs = new UUID[][]{{W1, PARCEL}, {W1, SEND}, {W2, PARCEL}, {W2, FINANCE}, {W3, SEND}, {W3, FINANCE}};
        for (var p : pairs) {
            jdbc.update("INSERT INTO window_services(window_id, service_id) VALUES (?,?)", p[0], p[1]);
        }
    }

    /** Конфигурация очереди на момент первого теста: тесты BRANCH_INBOX меняют её, остальные не должны это видеть. */
    private static java.util.Map<String, Object> configSnapshot;

    private void restoreConfig() {
        if (configSnapshot == null) {
            configSnapshot = jdbc.queryForMap("SELECT central_version, central_config::text AS c, effective_version FROM branch_config WHERE id=1");
            return;
        }
        jdbc.update("""
                UPDATE branch_config SET central_version=?, central_config=CAST(? AS jsonb), local_overrides='{}', bounds='{}',
                       effective_version=? WHERE id=1
                """, configSnapshot.get("central_version"), configSnapshot.get("c"), configSnapshot.get("effective_version"));
    }

    protected void openWindow(UUID window, UUID employee, UUID... services) {
        windows.open(window, List.of(services), employee);
    }

    /** QR-талон с уникальным устройством. */
    protected UUID qr(UUID service, String device) {
        return issue.execute(new IssueTicketCommand(TicketSource.qr, service, null, "default", device, null),
                "qr-" + device + "-" + service).ticketId();
    }

    protected UUID qr(UUID service, String device, String category) {
        return issue.execute(new IssueTicketCommand(TicketSource.qr, service, null, category, device, null),
                "qr-" + device + "-" + service).ticketId();
    }

    protected honey.road.contracts.dto.TicketView qrView(UUID service, String device) {
        return issue.execute(new IssueTicketCommand(TicketSource.qr, service, null, "default", device, null),
                "qr-" + device + "-" + service);
    }

    protected String status(UUID ticket) {
        return jdbc.queryForObject("SELECT status FROM tickets WHERE id=?", String.class, ticket);
    }

    protected long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
