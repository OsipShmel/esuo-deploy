package honey.road.center.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(CenterProperties.class)
public class CenterConfiguration {
    @Bean
    Clock clock() { return Clock.systemUTC(); }
}
