package honey.road.security.demo;

import honey.road.config.DatabaseBootstrap;
import honey.road.config.NodeProperties;
import honey.road.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Демо-пользователи ноды: {@code operator} и {@code admin}. Только при {@code esuo.demo-data=true}.
 * Пароли приходят из env и хешируются при старте — в коде и миграциях их нет.
 * В целевой схеме пользователи приходят из центра через CONFIG (раздел 13.2).
 */
@Component
@Order(DatabaseBootstrap.ORDER + 10)
@ConditionalOnProperty(name = "esuo.demo-data", havingValue = "true")
class DemoUsersSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoUsersSeeder.class);

    /** Сотрудник из демо-справочника {@link DatabaseBootstrap}. */
    private static final UUID DEMO_EMPLOYEE = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID OPERATOR_ID = UUID.fromString("00000000-0000-4000-8000-000000000011");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-4000-8000-000000000012");

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final NodeProperties node;

    DemoUsersSeeder(JdbcClient jdbc, PasswordEncoder passwordEncoder, NodeProperties node) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.node = node;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        upsert(OPERATOR_ID, "operator", node.demoUsers().operatorPassword(), Role.OPERATOR);
        upsert(ADMIN_ID, "admin", node.demoUsers().adminPassword(), Role.BRANCH_ADMIN);
    }

    private void upsert(UUID id, String login, String password, Role role) {
        if (password == null || password.isBlank()) {
            log.warn("demo user skipped: empty password login={}", login);
            return;
        }
        // DO UPDATE: смена пароля в .env применяется перезапуском.
        jdbc.sql("""
                        INSERT INTO users (id, login, password_hash, role, employee_id, active)
                        VALUES (:id, :login, :hash, :role, :employee, true)
                        ON CONFLICT (login) DO UPDATE
                           SET password_hash = EXCLUDED.password_hash, role = EXCLUDED.role, active = true
                        """)
                .param("id", id)
                .param("login", login)
                .param("hash", passwordEncoder.encode(password))
                .param("role", role.name())
                .param("employee", DEMO_EMPLOYEE)
                .update();
        log.info("demo user ready login={} role={}", login, role);
    }
}
