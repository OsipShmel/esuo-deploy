package honey.road.center.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@ConfigurationProperties("esuo.center")
public record CenterProperties(String natsUrl, Duration rpcTimeout, Duration offlineAfter, List<String> corsOrigins,
                               @Name("notify") Notify notification, Mp mp) {
    public CenterProperties {
        natsUrl = natsUrl == null || natsUrl.isBlank() ? "nats://localhost:4222" : natsUrl;
        rpcTimeout = rpcTimeout == null ? Duration.ofSeconds(3) : rpcTimeout;
        offlineAfter = offlineAfter == null ? Duration.ofSeconds(30) : offlineAfter;
        corsOrigins = corsOrigins == null ? List.of() : List.copyOf(corsOrigins);
        notification = notification == null ? new Notify(null, null, null, null, 0, null, null) : notification;
        mp = mp == null ? new Mp(null, null, null, null, null, 0, null, null) : mp;
    }

    /** Notification delivery (sect 11.3). */
    public record Notify(String url, Duration connectTimeout, Duration requestTimeout, Duration messageTtl, int maxDeliver,
                         List<Duration> backoff, CircuitBreaker circuitBreaker) {
        public Notify {
            url = url == null || url.isBlank() ? "http://notify-mock:8090/notify" : url;
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
            requestTimeout = requestTimeout == null ? Duration.ofSeconds(5) : requestTimeout;
            messageTtl = messageTtl == null ? Duration.ofMinutes(15) : messageTtl;
            maxDeliver = maxDeliver < 1 ? 6 : maxDeliver;
            backoff = backoff == null || backoff.isEmpty() ? seconds(1, 2, 5, 15, 30) : List.copyOf(backoff);
            circuitBreaker = circuitBreaker == null ? new CircuitBreaker(0, 0, 0, null, 0) : circuitBreaker;
        }
    }

    /** MP webhook and callback (sect 4.6, 13.2). */
    public record Mp(String webhookSecret, String callbackUrl, String publicUrl, Duration timestampTolerance,
                     Duration requestTimeout, int maxDeliver, List<Duration> backoff, CircuitBreaker circuitBreaker) {
        public Mp {
            webhookSecret = webhookSecret == null ? "" : webhookSecret;
            callbackUrl = callbackUrl == null || callbackUrl.isBlank() ? "http://mp-simulator:8091/callback" : callbackUrl;
            publicUrl = publicUrl == null || publicUrl.isBlank() ? "http://localhost:8081" : publicUrl.replaceAll("/+$", "");
            timestampTolerance = timestampTolerance == null ? Duration.ofMinutes(5) : timestampTolerance;
            requestTimeout = requestTimeout == null ? Duration.ofSeconds(5) : requestTimeout;
            maxDeliver = maxDeliver < 1 ? 10 : maxDeliver;
            backoff = backoff == null || backoff.isEmpty() ? seconds(1, 2, 5, 15, 30, 60) : List.copyOf(backoff);
            circuitBreaker = circuitBreaker == null ? new CircuitBreaker(0, 0, 0, null, 0) : circuitBreaker;
        }
    }

    public record CircuitBreaker(float failureRateThreshold, int slidingWindowSize, int minimumCalls,
                                 Duration waitInOpenState, int permittedCallsInHalfOpen) {
        public CircuitBreaker {
            failureRateThreshold = failureRateThreshold <= 0 ? 50 : failureRateThreshold;
            slidingWindowSize = slidingWindowSize < 1 ? 10 : slidingWindowSize;
            minimumCalls = minimumCalls < 1 ? 5 : minimumCalls;
            waitInOpenState = waitInOpenState == null ? Duration.ofSeconds(30) : waitInOpenState;
            permittedCallsInHalfOpen = permittedCallsInHalfOpen < 1 ? 5 : permittedCallsInHalfOpen;
        }
    }

    private static List<Duration> seconds(long... values) {
        return Arrays.stream(values).mapToObj(Duration::ofSeconds).toList();
    }
}
