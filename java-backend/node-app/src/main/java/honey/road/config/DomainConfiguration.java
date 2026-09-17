package honey.road.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
@EnableConfigurationProperties({honey.road.config.QueueProperties.class, honey.road.config.NodeProperties.class})
public class DomainConfiguration {
    @Bean
    Clock clock() { return Clock.systemUTC(); }
}
