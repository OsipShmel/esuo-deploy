package honey.road.center.nats;

import honey.road.center.ingest.EventIngest;
import honey.road.center.sse.CenterTicketStreams;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** {@code /actuator/health} → {@code nats}: связь с hub, стримы, ingest, число SSE-клиентов. */
@Component("nats")
public class CenterHealthIndicator implements HealthIndicator {
    private final CenterNats nats;
    private final CenterStreams streams;
    private final EventIngest ingest;
    private final CenterTicketStreams sse;

    public CenterHealthIndicator(CenterNats nats, CenterStreams streams, EventIngest ingest, CenterTicketStreams sse) {
        this.nats = nats; this.streams = streams; this.ingest = ingest; this.sse = sse;
    }

    @Override
    public Health health() {
        var builder = nats.connection().isPresent() ? Health.up() : Health.down();
        return builder.withDetail("state", nats.state())
                .withDetail("streams_ready", streams.ready())
                .withDetail("ingest_running", ingest.running())
                .withDetail("sse_connections", sse.connections())
                .build();
    }
}
