package honey.road.center.it;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base of center integration tests: real PostgreSQL 16 (Testcontainers), full Spring context, MockMvc.
 * No NATS: the center starts without the hub and connects in the background, so HTTP and the database are testable alone.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "esuo.center.nats-url=nats://127.0.0.1:1",
        "esuo.center.security.jwt-secret=" + AbstractCenterIT.JWT_SECRET,
        "esuo.center.security.admin-login=" + AbstractCenterIT.ADMIN_LOGIN,
        "esuo.center.security.admin-password=" + AbstractCenterIT.ADMIN_PASSWORD,
        "esuo.center.mp.webhook-secret=" + AbstractCenterIT.MP_SECRET,
        // partitions only by explicit runs: a background run on the real clock would race MetricsPartitionsIT
        "esuo.center.metrics.partitions-initial-delay=1h", "esuo.center.metrics.partitions-every=1h"
})
public abstract class AbstractCenterIT {

    public static final String JWT_SECRET = "aW50ZWdyYXRpb24tdGVzdC1zZWNyZXQtMDEyMzQ1Njc4OWFiY2RlZg==";
    public static final String ADMIN_LOGIN = "network";
    public static final String ADMIN_PASSWORD = "test-admin-pass";
    public static final String MP_SECRET = "test-mp-secret";

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

    @Autowired protected MockMvcTester mvc;

    protected final JsonMapper json = JsonMapper.builder().build();

    /** Logs in as the configured center admin and returns the bearer token. */
    protected String adminToken() {
        var result = mvc.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"" + ADMIN_LOGIN + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}")
                .exchange();
        result.assertThat().hasStatusOk();
        return json.readTree(result.getResponse().getContentAsByteArray()).path("access_token").asString();
    }
}
