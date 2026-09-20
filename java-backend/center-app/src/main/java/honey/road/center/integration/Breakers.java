package honey.road.center.integration;

import honey.road.center.config.CenterProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

/** Resilience4j core circuit breakers (sect 11.3); retries are left to JetStream redelivery. */
public final class Breakers {
    private Breakers() {
    }

    public static CircuitBreaker of(String name, CenterProperties.CircuitBreaker p) {
        return CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .failureRateThreshold(p.failureRateThreshold())
                .slidingWindowSize(p.slidingWindowSize())
                .minimumNumberOfCalls(p.minimumCalls())
                .waitDurationInOpenState(p.waitInOpenState())
                .permittedNumberOfCallsInHalfOpenState(p.permittedCallsInHalfOpen())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build());
    }
}
