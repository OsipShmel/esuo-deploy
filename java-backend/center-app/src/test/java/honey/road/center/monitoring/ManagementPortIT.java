package honey.road.center.monitoring;

import honey.road.center.it.AbstractCenterIT;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Metrics of the center for Prometheus (scale design 6.2): on the management port only, which compose does not
 * publish; the main port keeps a readiness probe for scripts and Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "esuo.center.nats-url=nats://127.0.0.1:1",
        "esuo.center.security.jwt-secret=" + AbstractCenterIT.JWT_SECRET,
        "esuo.center.security.admin-login=" + AbstractCenterIT.ADMIN_LOGIN,
        "esuo.center.security.admin-password=" + AbstractCenterIT.ADMIN_PASSWORD,
        "esuo.center.mp.webhook-secret=" + AbstractCenterIT.MP_SECRET,
        "esuo.center.metrics.partitions-initial-delay=1h", "esuo.center.metrics.partitions-every=1h",
        "management.server.port=0"
})
class ManagementPortIT extends AbstractCenterIT {
    @LocalServerPort int port;
    @LocalManagementPort int managementPort;

    private final RestClient http = RestClient.builder().defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
            .build();

    private String get(int on, String path) {
        return http.get().uri("http://localhost:" + on + path).retrieve().body(String.class);
    }

    private int status(int on, String path) {
        return http.get().uri("http://localhost:" + on + path).retrieve().toBodilessEntity().getStatusCode().value();
    }

    @Test
    void prometheusIsServedOnTheManagementPort() {
        var body = get(managementPort, "/actuator/prometheus");

        assertThat(body).contains("esuo_heartbeats_received_total", "esuo_heartbeats_buffered",
                "esuo_heartbeat_flush_seconds_count", "esuo_rules_pass_seconds", "esuo_rules_alerts",
                "esuo_onboarding_queue", "esuo_events_ingested_total", "esuo_branches{state=\"online\"}",
                "esuo_metrics_history_bytes", "jvm_gc_", "hikaricp_connections_active");
    }

    @Test
    void mainPortDoesNotServeMetrics() {
        assertThat(status(port, "/actuator/prometheus")).isIn(401, 404);
    }

    @Test
    void mainPortKeepsAReadinessProbe() {
        assertThat(status(port, "/readyz")).isEqualTo(200);
        assertThat(status(managementPort, "/actuator/health")).isIn(200, 503);    // 503: no hub in tests
        assertThat(get(managementPort, "/actuator/health")).contains("\"nats\"");
    }
}
