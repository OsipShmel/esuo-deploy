package honey.road.center.config;

import honey.road.center.monitoring.MonitoringConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({CenterProperties.class, MonitoringConfig.class})
public class CenterConfiguration {
    @Bean
    Clock clock() { return Clock.systemUTC(); }
}
