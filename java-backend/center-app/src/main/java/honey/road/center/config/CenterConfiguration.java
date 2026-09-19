package honey.road.center.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(CenterProperties.class)
public class CenterConfiguration {
    @Bean
    Clock clock() { return Clock.systemUTC(); }

    @Bean
    WebMvcConfigurer cors(CenterProperties props) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(props.corsOrigins().toArray(String[]::new))
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE")
                        .allowedHeaders("Content-Type", "Idempotency-Key", "X-Request-Id", "Last-Event-ID", "Authorization")
                        .exposedHeaders("X-Request-Id");
            }
        };
    }
}
