package honey.road.center.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties("esuo.center")
public record CenterProperties(String natsUrl, Duration rpcTimeout, Duration offlineAfter, List<String> corsOrigins) {
    public CenterProperties {
        natsUrl = natsUrl == null || natsUrl.isBlank() ? "nats://localhost:4222" : natsUrl;
        rpcTimeout = rpcTimeout == null ? Duration.ofSeconds(3) : rpcTimeout;
        offlineAfter = offlineAfter == null ? Duration.ofSeconds(30) : offlineAfter;
        corsOrigins = corsOrigins == null ? List.of() : List.copyOf(corsOrigins);
    }
}
