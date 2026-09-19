package honey.road.node.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Настройки ноды отделения (префикс {@code esuo}). Нода обслуживает ровно одно отделение.
 * Регистрируется в {@link DomainConfiguration}.
 */
@Validated
@ConfigurationProperties("esuo")
public record NodeProperties(
        @NotBlank @Pattern(regexp = "[a-zA-Z0-9_-]{1,64}") String branchId,
        String branchName,
        @NotBlank String timezone,
        boolean demoData,
        @NotNull @Valid Security security,
        DemoUsers demoUsers
) {
    public NodeProperties {
        demoUsers = demoUsers == null ? new DemoUsers(null, null) : demoUsers;
    }

    public record Security(
            @NotBlank String jwtSecret,        // base64, >= 256 бит; только из env
            Duration jwtTtl,
            List<String> corsOrigins
    ) {
        public Security {
            jwtTtl = jwtTtl == null ? Duration.ofHours(12) : jwtTtl;
            corsOrigins = corsOrigins == null ? List.of() : List.copyOf(corsOrigins);
        }
    }

    /** Пароли демо-пользователей; пустой пароль — пользователь не создаётся. */
    public record DemoUsers(String operatorPassword, String adminPassword) {
    }

    public String jwtIssuer() {
        return "branch-node-" + branchId;
    }
}
