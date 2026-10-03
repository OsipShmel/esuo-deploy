package honey.road.center.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@ConfigurationProperties("esuo.center")
public record CenterProperties(String natsUrl, Duration rpcTimeout, Duration offlineAfter, List<String> corsOrigins,
                               @Name("notify") Notify notification, Mp mp, Security security, Logs logs,
                               Metrics metrics, Network network, boolean demoMode) {
    public CenterProperties {
        natsUrl = natsUrl == null || natsUrl.isBlank() ? "nats://localhost:4222" : natsUrl;
        rpcTimeout = rpcTimeout == null ? Duration.ofSeconds(3) : rpcTimeout;
        offlineAfter = offlineAfter == null ? Duration.ofSeconds(30) : offlineAfter;
        corsOrigins = corsOrigins == null ? List.of() : List.copyOf(corsOrigins);
        notification = notification == null ? new Notify(null, null, null, null, 0, null, null) : notification;
        mp = mp == null ? new Mp(null, null, null, null, null, 0, null, null) : mp;
        security = security == null ? new Security(null, null, null, null, null) : security;
        logs = logs == null ? new Logs(null, 0, null, null, null, null, null, null, 0) : logs;
        metrics = metrics == null ? new Metrics(null, 0, null, null, null, null, null) : metrics;
        network = network == null ? new Network(null, 0) : network;
    }

    /**
     * The network screen (scale design 8.2): one picture of the network is shared by every tab and poll and taken anew
     * when older than three times its cost, at least a second and at most {@code snapshotMaxAge}; every card at once
     * ({@code /network/overview}) is refused above {@code overviewMax} branches.
     */
    public record Network(Duration snapshotMaxAge, int overviewMax) {
        public Network {
            snapshotMaxAge = snapshotMaxAge == null ? Duration.ofSeconds(5) : snapshotMaxAge;
            overviewMax = overviewMax <= 0 ? 1000 : overviewMax;
        }
    }

    /**
     * Heartbeats in batches and the metrics history (design 6.2, 6.3, scale design 5.1). {@code resolution} 10 s keeps
     * every heartbeat of an ordinary branch; {@code resolutionRegional} is for branches behind a regional hub (the
     * swarm, a mass rollout), one row a minute by default. A flush writes {@code flushChunk} branches per transaction:
     * the memory and the transaction of a flush do not grow with the network. {@code maxSize} is a safety net for the disk: over it the
     * oldest hours go before {@code retention}.
     */
    public record Metrics(Duration flushInterval, int flushChunk, Duration resolution, Duration resolutionRegional,
                          Duration retention, DataSize maxSize, Duration maxRange) {
        public Metrics {
            flushInterval = flushInterval == null ? Duration.ofSeconds(5) : flushInterval;
            flushChunk = flushChunk <= 0 ? 5000 : flushChunk;
            resolution = resolution == null ? Duration.ofSeconds(10) : resolution;
            resolutionRegional = resolutionRegional == null ? Duration.ofSeconds(60) : resolutionRegional;
            retention = retention == null ? Duration.ofHours(48) : retention;
            maxSize = maxSize == null ? DataSize.ofGigabytes(100) : maxSize;
            maxRange = maxRange == null ? Duration.ofHours(48) : maxRange;
        }
    }

    /**
     * Branch logs (design 5.5–5.7): CENTER_LOG limits and shipping to Loki. An empty {@code lokiUrl} turns shipping off;
     * the lines then wait in CENTER_LOG.
     */
    public record Logs(String lokiUrl, int batchSize, Duration batchWait, List<Duration> backoff, Duration connectTimeout,
                       Duration requestTimeout, String grafanaPublicUrl, Duration retention, long maxBytes) {
        public Logs {
            lokiUrl = lokiUrl == null ? "" : lokiUrl.trim().replaceAll("/+$", "");
            batchSize = batchSize < 1 ? 500 : batchSize;
            // jnats refuses a pull shorter than 1 s; the batch window cannot go below that
            batchWait = batchWait == null || batchWait.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : batchWait;
            backoff = backoff == null || backoff.isEmpty() ? seconds(1, 2, 5, 15, 30) : List.copyOf(backoff);
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
            requestTimeout = requestTimeout == null ? Duration.ofSeconds(10) : requestTimeout;
            grafanaPublicUrl = grafanaPublicUrl == null ? "" : grafanaPublicUrl.trim().replaceAll("/+$", "");
            retention = retention == null ? Duration.ofDays(7) : retention;
            maxBytes = maxBytes <= 0 ? 2L * 1024 * 1024 * 1024 : maxBytes;
        }
    }

    /**
     * Center API access (design 4.2): one network admin account from the environment, HS256 tokens.
     * An empty password means no account at all, so the private API stays closed rather than open.
     */
    public record Security(String jwtSecret, Duration jwtTtl, String issuer, String adminLogin, String adminPassword) {
        public Security {
            jwtSecret = jwtSecret == null ? "" : jwtSecret.trim();
            jwtTtl = jwtTtl == null ? Duration.ofHours(12) : jwtTtl;
            issuer = issuer == null || issuer.isBlank() ? "esuo-center" : issuer;
            adminLogin = adminLogin == null || adminLogin.isBlank() ? "network" : adminLogin.trim();
            adminPassword = adminPassword == null ? "" : adminPassword;
        }

        @Override
        public String toString() {
            return "Security[issuer=%s, adminLogin=%s, jwtTtl=%s]".formatted(issuer, adminLogin, jwtTtl);
        }
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
