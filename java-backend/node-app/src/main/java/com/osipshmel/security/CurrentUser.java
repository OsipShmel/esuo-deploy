package com.osipshmel.security;

import jakarta.annotation.Nullable;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/**
 * В контроллере: {@code @AuthenticationPrincipal Jwt jwt} → {@code CurrentUser.of(jwt)}.
 **/
public record CurrentUser(UUID userId, String login, Role role, @Nullable UUID employeeId) {

    public static CurrentUser of(Jwt jwt) {
        var employee = jwt.getClaimAsString(JwtClaims.EMPLOYEE_ID);
        return new CurrentUser(
                UUID.fromString(jwt.getSubject()),
                jwt.getClaimAsString(JwtClaims.LOGIN),
                Role.valueOf(jwt.getClaimAsString(JwtClaims.ROLE)),
                employee == null ? null : UUID.fromString(employee));
    }
}
