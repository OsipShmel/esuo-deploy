package honey.road.center.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json31;
import honey.road.center.security.CenterSecurityConfig;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.util.AntPathMatcher;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

/**
 * springdoc builds schemas with its own Jackson 2 mapper and ignores {@code spring.jackson} (Jackson 3),
 * so snake_case is set here explicitly.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {
    private static final String BEARER = "bearerAuth";

    @Bean
    ModelResolver snakeCaseModelResolver() {
        return new ModelResolver(Json31.mapper().copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE))
                .openapi31(true);
    }

    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info().title("ESUO center API").version("v1"))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                        .description("POST /api/v1/auth/login, role NETWORK_ADMIN")));
    }

    /** Marks every operation outside the public list as requiring a token — the same list the security filter uses. */
    @Bean
    OpenApiCustomizer bearerOnPrivateOperations() {
        var matcher = new AntPathMatcher();
        return api -> api.getPaths().forEach((path, item) -> {
            var pattern = path.replaceAll("\\{[^}]+}", "x");
            boolean isPublic = Arrays.stream(CenterSecurityConfig.PUBLIC_PATHS).anyMatch(p -> matcher.match(p, pattern));
            if (!isPublic) {
                item.readOperations().forEach(op -> op.addSecurityItem(new SecurityRequirement().addList(BEARER)));
            }
        });
    }
}
