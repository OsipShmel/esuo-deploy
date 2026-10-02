package honey.road.center.it;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * No {@code CENTER_ADMIN_PASSWORD} (an old {@code .env}): the center still starts and serves the public API, but
 * nobody can log in and the private API stays closed — failing closed, never open.
 */
class CenterNoAdminIT extends AbstractCenterIT {

    @DynamicPropertySource
    static void noAdmin(DynamicPropertyRegistry registry) {
        registry.add("esuo.center.security.admin-password", () -> "");
    }

    @Test
    void loginIsImpossible() {
        mvc.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"" + ADMIN_LOGIN + "\",\"password\":\"anything\"}")
                .exchange().assertThat().hasStatus(401);
    }

    @Test
    void privateApiStaysClosed() {
        mvc.get().uri("/api/v1/network/overview").exchange().assertThat().hasStatus(401);
    }

    @Test
    void publicApiWorks() {
        mvc.get().uri("/api/v1/branches").exchange().assertThat().hasStatusOk();
    }
}
