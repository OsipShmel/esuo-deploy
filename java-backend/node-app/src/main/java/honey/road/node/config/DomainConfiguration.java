package honey.road.node.config;

import honey.road.node.messaging.nats.NatsProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({QueueProperties.class, NodeProperties.class, NatsProperties.class})
public class DomainConfiguration {
    @Bean
    Clock clock() { return Clock.systemUTC(); }
}
