package honey.road.center.it;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import honey.road.center.integration.HmacSigner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Center API access (design 4.1–4.2): an explicit public list, everything else under {@code /api/**} needs the
 * {@code NETWORK_ADMIN} role carried by a center-issued token.
 */
class CenterSecurityIT extends AbstractCenterIT {

    private static final String TICKET = UUID.randomUUID().toString();

    record Call(HttpMethod method, String uri) {
        @Override
        public String toString() { return method + " " + uri; }
    }

    static Stream<Call> publicRequests() {
        return Stream.of(
                new Call(HttpMethod.GET, "/api/v1/branches"),
                new Call(HttpMethod.GET, "/api/v1/branches/b01/info"),
                new Call(HttpMethod.GET, "/api/v1/branches/b01/slots?service_id=" + UUID.randomUUID() + "&date=2026-10-02"),
                new Call(HttpMethod.GET, "/api/v1/branches/b01/booking-limits?date=2026-10-02"),
                new Call(HttpMethod.POST, "/api/v1/branches/b01/qr-tickets"),
                new Call(HttpMethod.POST, "/api/v1/branches/b01/appointments"),
                new Call(HttpMethod.GET, "/api/v1/branches/b01/tickets/" + TICKET),
                new Call(HttpMethod.POST, "/api/v1/branches/b01/tickets/" + TICKET + "/cancel"),
                new Call(HttpMethod.POST, "/api/v1/branches/b01/tickets/" + TICKET + "/postpone"),
                new Call(HttpMethod.POST, "/api/v1/branches/b01/tickets/" + TICKET + "/check-in"),
                new Call(HttpMethod.GET, "/readyz"),
                new Call(HttpMethod.GET, "/v3/api-docs"));
    }

    static Stream<Call> privateRequests() {
        var user = UUID.randomUUID();
        return Stream.of(
                new Call(HttpMethod.GET, "/api/v1/network/overview"),
                new Call(HttpMethod.GET, "/api/v1/config"),
                new Call(HttpMethod.PUT, "/api/v1/config/global"),
                new Call(HttpMethod.GET, "/api/v1/config/versions"),
                new Call(HttpMethod.GET, "/api/v1/config/branches/b01"),
                new Call(HttpMethod.PUT, "/api/v1/config/branches/b01"),
                new Call(HttpMethod.GET, "/api/v1/branches/b01/users"),
                new Call(HttpMethod.POST, "/api/v1/branches/b01/users"),
                new Call(HttpMethod.PATCH, "/api/v1/branches/b01/users/" + user),
                new Call(HttpMethod.GET, "/api/v1/admin/dlq"),
                new Call(HttpMethod.POST, "/api/v1/admin/dlq/1/retry"),
                new Call(HttpMethod.GET, "/api/v1/admin/notifications/dlq"),
                new Call(HttpMethod.GET, "/api/v1/admin/notifications/status"),
                new Call(HttpMethod.GET, "/api/v1/admin/mp/requests"),
                new Call(HttpMethod.GET, "/actuator/info"));
    }

    private MvcTestResult call(Call c, String token) {
        var request = mvc.method(c.method()).uri(c.uri()).contentType(MediaType.APPLICATION_JSON).content("{}")
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return request.exchange();
    }

    private JsonNode body(MvcTestResult result) {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private MvcTestResult login(String login, String password) {
        return mvc.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"" + login + "\",\"password\":\"" + password + "\"}").exchange();
    }

    private static String token(String issuer, String role) {
        var key = new SecretKeySpec(Base64.getDecoder().decode(JWT_SECRET), "HmacSHA256");
        var now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(issuer).subject("someone").issuedAt(now)
                .expiresAt(now.plus(Duration.ofHours(1))).claim("login", "someone").claim("role", role)
                .claim("branch_id", "b01").build();
        return new NimbusJwtEncoder(new ImmutableSecret<>(key))
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Test
    void loginIssuesCenterToken() {
        var result = login(ADMIN_LOGIN, ADMIN_PASSWORD);
        result.assertThat().hasStatusOk();
        var body = body(result);
        assertThat(body.path("role").asString()).isEqualTo("NETWORK_ADMIN");
        assertThat(body.path("token_type").asString()).isEqualTo("Bearer");
        assertThat(body.path("access_token").asString()).isNotBlank();
        assertThat(body.path("employee_id").isNull()).isTrue();
        assertThat(Instant.parse(body.path("expires_at").asString()))
                .isCloseTo(Instant.now().plus(Duration.ofHours(12)), within(Duration.ofMinutes(1)));
    }

    @Test
    void wrongPasswordIs401() {
        var result = login(ADMIN_LOGIN, "wrong-password");
        result.assertThat().hasStatus(401);
        assertThat(body(result).path("code").asString()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void unknownLoginIs401() {
        login("nobody", ADMIN_PASSWORD).assertThat().hasStatus(401);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("publicRequests")
    void publicEndpointsPassSecurity(Call c) {
        int status = call(c, null).getResponse().getStatus();
        assertThat(status).as(c.toString()).isNotIn(401, 403);
    }

    @Test
    void ticketStreamIsPublic() {
        var result = mvc.get().uri("/api/v1/branches/b01/tickets/" + TICKET + "/stream")
                .accept(MediaType.TEXT_EVENT_STREAM).exchange();
        assertThat(result.getResponse().getStatus()).isNotIn(401, 403);
    }

    @Test
    void signedMpWebhookReachesController() {
        var payload = ("{\"mp_appointment_id\":\"mp-sec-1\",\"branch_id\":\"zz99\",\"service_code\":\"parcel\","
                + "\"slot_start\":\"2026-10-02T10:00:00Z\"}").getBytes(StandardCharsets.UTF_8);
        var ts = String.valueOf(Instant.now().getEpochSecond());
        var result = mvc.post().uri("/api/v1/webhooks/mp/appointments").contentType(MediaType.APPLICATION_JSON)
                .header(HmacSigner.TIMESTAMP, ts).header(HmacSigner.SIGNATURE, HmacSigner.sign(MP_SECRET, ts, payload))
                .content(payload).exchange();
        result.assertThat().hasStatus(404);
        assertThat(body(result).path("code").asString()).isEqualTo("BRANCH_NOT_FOUND");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("privateRequests")
    void privateEndpointsRequireToken(Call c) {
        var result = call(c, null);
        assertThat(result.getResponse().getStatus()).as(c.toString()).isEqualTo(401);
        assertThat(body(result).path("code").asString()).as(c.toString()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void nodeTokenIsRejected() {
        var result = call(new Call(HttpMethod.GET, "/api/v1/network/overview"), token("esuo-node-b01", "BRANCH_ADMIN"));
        result.assertThat().hasStatus(401);
    }

    @Test
    void tokenWithoutAdminRoleIsForbidden() {
        var result = call(new Call(HttpMethod.GET, "/api/v1/network/overview"), token("esuo-center", "OPERATOR"));
        result.assertThat().hasStatus(403);
        assertThat(body(result).path("code").asString()).isEqualTo("FORBIDDEN");
    }

    @Test
    void adminTokenOpensPrivateApi() {
        call(new Call(HttpMethod.GET, "/api/v1/network/overview"), adminToken()).assertThat().hasStatusOk();
    }
}
