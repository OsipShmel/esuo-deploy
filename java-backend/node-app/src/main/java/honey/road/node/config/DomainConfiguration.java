package honey.road.node.config;

import honey.road.node.api.publicapi.QrProperties;
import honey.road.node.messaging.nats.NatsProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import honey.road.node.demo.DemoClock;
import honey.road.node.demo.DemoRunnerProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({QueueProperties.class, NodeProperties.class, NatsProperties.class, QrProperties.class,
        DemoRunnerProperties.class})
public class DomainConfiguration {
    /** Real time: JWT, RPC deadlines. */
    @Bean
    Clock realClock() { return Clock.systemUTC(); }

    /** Domain time: {@link DemoClock} with {@code DEMO_MODE=true}, real time otherwise. */
    @Bean
    @Primary
    Clock clock(NodeProperties node, @Qualifier("realClock") Clock realClock) {
        return node.demoMode() ? new DemoClock(realClock) : realClock;
    }
}
