package honey.road.node.security;

import jakarta.annotation.Nullable;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/**
 * В контроллере: {@code @AuthenticationPrincipal Jwt jwt} → {@code CurrentUser.of(jwt)}.
 **/
public record CurrentUser(UUID userId, String login, honey.road.node.security.Role role, @Nullable UUID employeeId) {

    public static CurrentUser of(Jwt jwt) {
        var employee = jwt.getClaimAsString(honey.road.node.security.JwtClaims.EMPLOYEE_ID);
        return new CurrentUser(
                UUID.fromString(jwt.getSubject()),
                jwt.getClaimAsString(honey.road.node.security.JwtClaims.LOGIN),
                honey.road.node.security.Role.valueOf(jwt.getClaimAsString(honey.road.node.security.JwtClaims.ROLE)),
                employee == null ? null : UUID.fromString(employee));
    }
}
