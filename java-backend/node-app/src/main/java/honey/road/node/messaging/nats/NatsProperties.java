package honey.road.node.messaging.nats;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Node link to the local NATS leaf ({@code esuo.nats}). Only the leaf knows the hub address.
 */
@ConfigurationProperties("esuo.nats")
public record NatsProperties(
        String url,
        Duration connectTimeout,
        Duration reconnectWait,
        Duration publishTimeout,
        Outbox outbox,
        Duration heartbeatInterval,
        Duration eventRetention
) {
    public NatsProperties {
        url = url == null || url.isBlank() ? "nats://localhost:4222" : url;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        reconnectWait = reconnectWait == null ? Duration.ofSeconds(1) : reconnectWait;
        publishTimeout = publishTimeout == null ? Duration.ofSeconds(3) : publishTimeout;
        outbox = outbox == null ? new Outbox(null, 0, null, null) : outbox;
        heartbeatInterval = heartbeatInterval == null ? Duration.ofSeconds(10) : heartbeatInterval;
        eventRetention = eventRetention == null ? Duration.ofDays(7) : eventRetention;
    }

    /**
     * @param pollInterval  pause between worker passes
     * @param batchSize     rows per pass
     * @param maxBackoff    cap of the exponential delay after a publish error
     * @param sentRetention how long sent rows are kept
     */
    public record Outbox(Duration pollInterval, int batchSize, Duration maxBackoff, Duration sentRetention) {
        public Outbox {
            pollInterval = pollInterval == null ? Duration.ofMillis(200) : pollInterval;
            batchSize = batchSize <= 0 ? 100 : batchSize;
            maxBackoff = maxBackoff == null ? Duration.ofSeconds(30) : maxBackoff;
            sentRetention = sentRetention == null ? Duration.ofHours(24) : sentRetention;
        }
    }
}
