package honey.road.config;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * branch-node settings. The node serves exactly one branch;
 */
@Validated
@ConfigurationProperties("esuo.node")
public record NodeProperties2(
        @Pattern(regexp = "[a-z0-9-]{2,32}") String branchId,
        Security security
) {
    public record Security(
            @NotEmpty String jwtSecret,
            Duration jwtTtl,
            List<String> corsOrigins
    ) {
        public Security {
            jwtTtl = jwtTtl == null ? Duration.ofHours(12) : jwtTtl;
            corsOrigins = corsOrigins == null ? List.of() : List.copyOf(corsOrigins);
        }
    }

    //TODO!
    public String jwtIssuer() {
        return "branch-node-" + branchId;
    }
}
