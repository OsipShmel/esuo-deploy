package honey.road.center.monitoring;

import honey.road.center.it.AbstractCenterIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Grafana reads the center database as {@code grafana_ro}: the monitoring tables and nothing else (design 6.5). */
@TestPropertySource(properties = "esuo.center.grafana.db-password=" + GrafanaRoleIT.PASSWORD)
class GrafanaRoleIT extends AbstractCenterIT {
    static final String PASSWORD = "ro-pass'x";                         // a quote must not break the statement

    @Value("${spring.datasource.url}") String url;
    @Autowired GrafanaRole role;

    private Connection asGrafana() throws SQLException {
        return DriverManager.getConnection(url, "grafana_ro", PASSWORD);
    }

    private static long count(Connection c, String table) throws SQLException {
        try (var rs = c.createStatement().executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void roleCanReadMonitoringTables() throws Exception {
        try (var c = asGrafana()) {
            assertThat(count(c, "branch_metrics")).isNotNegative();
            assertThat(count(c, "branch_alerts")).isNotNegative();
            assertThat(count(c, "branches")).isNotNegative();
        }
    }

    @Test
    void roleCannotReadOtherTables() throws Exception {
        try (var c = asGrafana()) {
            assertThatThrownBy(() -> count(c, "events")).isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
        }
    }

    @Test
    void roleCannotWrite() throws Exception {
        try (var c = asGrafana()) {
            assertThatThrownBy(() -> c.createStatement().executeUpdate("DELETE FROM branch_metrics"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
        }
    }

    @Test
    void rerunIsIdempotent() throws Exception {
        role.ensure();
        role.ensure();

        try (var c = asGrafana()) {
            assertThat(count(c, "branch_alerts")).isNotNegative();
        }
    }
}
