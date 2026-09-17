package honey.road.security;

import jakarta.annotation.Nullable;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/**
 * В контроллере: {@code @AuthenticationPrincipal Jwt jwt} → {@code CurrentUser.of(jwt)}.
 **/
public record CurrentUser(UUID userId, String login, honey.road.security.Role role, @Nullable UUID employeeId) {

    public static CurrentUser of(Jwt jwt) {
        var employee = jwt.getClaimAsString(honey.road.security.JwtClaims.EMPLOYEE_ID);
        return new CurrentUser(
                UUID.fromString(jwt.getSubject()),
                jwt.getClaimAsString(honey.road.security.JwtClaims.LOGIN),
                honey.road.security.Role.valueOf(jwt.getClaimAsString(honey.road.security.JwtClaims.ROLE)),
                employee == null ? null : UUID.fromString(employee));
    }
}
