package honey.road.swarm;

import honey.road.contracts.event.EventEnvelope;
import honey.road.contracts.event.Subjects;
import honey.road.contracts.http.EsuoHeaders;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamOptions;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.PublishOptions;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.impl.Headers;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Publishes the swarm into regional leaves, the way branch nodes publish into their own leaf:
 * <ul>
 *   <li>events — JetStream with {@code Nats-Msg-Id = event_id} (like {@code OutboxWorker}), captured by the
 *   regional {@code REGION_EVT}; asynchronous, at most {@code maxInFlight} unacknowledged;</li>
 *   <li>heartbeats — core NATS {@code branch.{b}.hb}, through the leaf to the hub;</li>
 *   <li>config — a core subscription to {@code center.config.branch.>}: the version a virtual branch reports in its
 *   heartbeat is the one the center really published, so the center does not resend 40 000 configs a minute.</li>
 * </ul>
 */
final class NatsSink implements EventSink, AutoCloseable {
    static final Duration EVENT_RETENTION = Duration.ofDays(7);

    private final JsonMapper json = mapper();
    private final Map<String, Connection> connections = new LinkedHashMap<>();
    private final Map<String, JetStream> jetStreams = new LinkedHashMap<>();
    private final Map<String, String> domains = new LinkedHashMap<>();
    private final Semaphore inFlight;
    private final int maxInFlight;
    private final SwarmStats stats;

    NatsSink(List<String> urls, int maxInFlight, SwarmStats stats) throws IOException, InterruptedException {
        this.maxInFlight = maxInFlight;
        this.inFlight = new Semaphore(maxInFlight);
        this.stats = stats;
        for (var url : urls) {
            if (connections.containsKey(url)) { continue; }
            var c = Nats.connect(Options.builder().server(url).connectionName("swarm-sim")
                    .maxReconnects(-1).reconnectWait(Duration.ofSeconds(1)).connectionTimeout(Duration.ofSeconds(5))
                    .reconnectBufferSize(64L * 1024 * 1024).build());
            connections.put(url, c);
            jetStreams.put(url, c.jetStream(JetStreamOptions.builder().requestTimeout(Duration.ofSeconds(10)).build()));
            domains.put(url, ensureRegionStream(url, c));
        }
    }

    /**
     * {@code REGION_EVT} in the leaf's own JetStream domain; the domain becomes {@code events_via} of its branches.
     * A connection to the hub itself (domain {@code center}) is refused: events would land next to {@code CENTER_EVT}
     * and never reach it.
     */
    private static String ensureRegionStream(String url, Connection c) throws IOException {
        try {
            var jsm = c.jetStreamManagement();
            var domain = jsm.getAccountStatistics().getDomain();
            if (domain == null || !domain.matches(Subjects.REGION_DOMAIN_PATTERN)) {
                throw new IllegalStateException(url + ": JetStream-домен «" + domain + "», а нужен региональный leaf "
                        + "(region-N). Поднимите их: docker compose --profile swarm up -d");
            }
            var config = StreamConfiguration.builder()
                    .name(Subjects.STREAM_REGION_EVT)
                    .subjects(Subjects.ALL_BRANCH_EVENTS)
                    .storageType(StorageType.File)
                    .maxAge(EVENT_RETENTION)
                    .duplicateWindow(Duration.ofHours(1))
                    .build();
            try {
                jsm.getStreamInfo(Subjects.STREAM_REGION_EVT);
                jsm.updateStream(config);
            } catch (JetStreamApiException e) {
                if (e.getApiErrorCode() != 10059) { throw e; }       // stream not found
                jsm.addStream(config);
            }
            return domain;
        } catch (JetStreamApiException e) {
            throw new IOException(url + ": " + e.getMessage(), e);
        }
    }

    /** The node's JSON: snake_case fields, ISO-8601 instants — what the center's ingest parses. */
    static JsonMapper mapper() {
        return JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    }

        String domain(String url) {
        return domains.get(url);
    }

    /** Branches learn the config version the center published for them. */
    void listenConfig(Map<String, VirtualBranch> byId) {
        var c = connections.values().iterator().next();
        c.createDispatcher(msg -> {
            var id = msg.getSubject().substring(msg.getSubject().lastIndexOf('.') + 1);
            var b = byId.get(id);
            if (b == null) { return; }
            try {
                int version = json.readTree(msg.getData()).path("version").asInt(0);
                if (version > b.configVersion) { b.configVersion = version; }
                stats.configs.increment();
            } catch (RuntimeException e) {
                stats.error(e);
            }
        }).subscribe(Subjects.CONFIG_ALL_BRANCHES);
    }

    @Override
    public void event(VirtualBranch branch, EventEnvelope e) {
        var url = branch.natsUrl;
        var body = json.writeValueAsBytes(e);
        var headers = new Headers().add(EsuoHeaders.CONTENT_TYPE, "application/json")
                .add(EsuoHeaders.REQUEST_ID, e.eventId().toString());
        try {
            inFlight.acquire();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return;
        }
        stats.inc(stats.published, e.eventType());
        try {
            jetStreams.get(url).publishAsync(e.subject(), headers, body,
                            PublishOptions.builder().messageId(e.eventId().toString()).build())
                    .whenComplete((ack, error) -> {
                        inFlight.release();
                        if (error != null) {
                            stats.inc(stats.failed, e.eventType());
                            stats.error(error);
                        } else if (ack.isDuplicate()) {
                            stats.duplicates.increment();
                        } else {
                            stats.inc(stats.acked, e.eventType());
                        }
                    });
        } catch (RuntimeException ex) {
            inFlight.release();
            stats.inc(stats.failed, e.eventType());
            stats.error(ex);
        }
    }

    @Override
    public void heartbeat(VirtualBranch branch, Map<String, Object> heartbeat) {
        try {
            connections.get(branch.natsUrl).publish(Subjects.heartbeat(branch.id),
                    new Headers().add(EsuoHeaders.CONTENT_TYPE, "application/json"), json.writeValueAsBytes(heartbeat));
            stats.heartbeats.increment();
        } catch (RuntimeException e) {
            stats.heartbeatErrors.increment();
            stats.error(e);
        }
    }

    int inFlight() {
        return maxInFlight - inFlight.availablePermits();
    }

    /** Waits for outstanding acks before the report is written. */
    boolean drain(Duration timeout) throws InterruptedException {
        if (!inFlight.tryAcquire(maxInFlight, timeout.toMillis(), TimeUnit.MILLISECONDS)) { return false; }
        inFlight.release(maxInFlight);
        return true;
    }

    @Override
    public void close() throws InterruptedException {
        for (var c : connections.values()) {
            try {
                c.flush(Duration.ofSeconds(5));
            } catch (java.util.concurrent.TimeoutException e) {
                stats.error(e);
            }
            c.close();
        }
    }
}
