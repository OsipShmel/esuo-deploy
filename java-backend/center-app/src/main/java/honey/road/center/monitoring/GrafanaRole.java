package honey.road.center.monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database role Grafana reads the center with (design 6.5): {@code SELECT} on the monitoring tables only. Created
 * or updated on every start when {@code GRAFANA_DB_PASSWORD} is set, so a changed password applies on restart. A
 * failure (no CREATEROLE right, for one) leaves the center running without Grafana's Postgres panels.
 */
@Component
public class GrafanaRole implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(GrafanaRole.class);
    static final String ROLE = "grafana_ro";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final String password;

    public GrafanaRole(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                       @Value("${esuo.center.grafana.db-password:}") String password) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(transactions); this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (password.isEmpty()) {
            log.info("grafana database role is off (GRAFANA_DB_PASSWORD is empty)");
            return;
        }
        ensure();
    }

    /** Idempotent; under an advisory lock, as several center replicas start at once. */
    public void ensure() {
        try {
            tx.executeWithoutResult(s -> {
                jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext('esuo.grafana.role'))", Object.class);
                jdbc.execute("""
                        DO $$ BEGIN
                          IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '%1$s') THEN CREATE ROLE %1$s LOGIN; END IF;
                          EXECUTE format('GRANT CONNECT ON DATABASE %%I TO %1$s', current_database());
                        END $$""".formatted(ROLE));
                // DDL takes no bind parameters: the literal is quoted by doubling, standard_conforming_strings is on
                jdbc.execute("ALTER ROLE " + ROLE + " WITH LOGIN PASSWORD '" + password.replace("'", "''") + "'");
                jdbc.execute("GRANT USAGE ON SCHEMA public TO " + ROLE);
                jdbc.execute("GRANT SELECT ON branches, branch_metrics, branch_alerts TO " + ROLE);
            });
            log.info("grafana database role {} ready: select on branches, branch_metrics, branch_alerts", ROLE);
        } catch (RuntimeException e) {
            log.warn("grafana database role {} not set up, Grafana has no Postgres panels: {}", ROLE, e.toString());
        }
    }
}
