package honey.road.node.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc builds schemas with its own Jackson 2 mapper and ignores {@code spring.jackson} (Jackson 3),
 * so snake_case is set here explicitly.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {
    @Bean
    ModelResolver snakeCaseModelResolver() {
        return new ModelResolver(Json31.mapper().copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE))
                .openapi31(true);
    }

    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info().title("ESUO branch node API").version("v1"));
    }
}
