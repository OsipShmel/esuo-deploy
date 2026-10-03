package honey.road.center.nats;

import honey.road.contracts.event.Subjects;
import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.api.External;
import io.nats.client.api.Source;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
* Streams from the {@code center} domain (docs, section 10.4) and the connection of branches to {@code CENTER_EVT}.
* <p>{@code CENTER_EVT} does not have its own subjects from {@code branch}: events arrive only via sources;
* otherwise, with an active connection, each event would be received twice. A branch source is added automatically
* upon the first heartbeat ({@link #attachBranch}); a branch whose events live in a regional leaf brings in that
* region's {@code REGION_EVT} instead, once per region ({@link #attachRegion}, design doc 3.8).
 */
@Component
public class CenterStreams {
    private static final Logger log = LoggerFactory.getLogger(CenterStreams.class);
    static final int STREAM_NOT_FOUND = 10059;

    private final CenterNats nats;
    private volatile boolean ready;
    /** Regions already sourced by this process: 40 000 heartbeats must not mean 40 000 stream-info calls. */
    private final Set<String> regions = ConcurrentHashMap.newKeySet();

    public CenterStreams(CenterNats nats) { this.nats = nats; }

    @PostConstruct
    void register() {
        nats.onConnected(this::ensure);
    }

    public boolean ready() { return ready; }

    void ensure(Connection c) {
        try {
            var jsm = c.jetStreamManagement();
            createIfAbsent(jsm, Subjects.STREAM_CENTER_EVT, b -> b
                    .subjects("center.evt.local")               // placeholder instead of the default subject; branch.> stays untouched
                    .storageType(StorageType.File)
                    .maxAge(Duration.ofDays(30))
                    .duplicateWindow(Duration.ofHours(1)));
            createIfAbsent(jsm, Subjects.STREAM_CONFIG, b -> b
                    .subjects("center.config.>")
                    .storageType(StorageType.File)
                    .maxMessagesPerSubject(1));
            createIfAbsent(jsm, Subjects.STREAM_MP_INBOX, b -> b
                    .subjects("center.mp.inbox.branch.*")
                    .storageType(StorageType.File)
                    .maxAge(Duration.ofHours(24))
                    .duplicateWindow(Duration.ofHours(24)));
            ready = true;
        } catch (IOException | JetStreamApiException e) {
            ready = false;
            log.error("cannot ensure center streams (is JetStream enabled on hub?)", e);
        }
    }

    /**
    * Connects the {@code BRANCH_EVT} branch to {@code CENTER_EVT}. Idempotent.
    * @return true if the source exists (was added now or previously)
     */
    public synchronized boolean attachBranch(String branchId) {
        return attachSource(Subjects.STREAM_BRANCH_EVT, Subjects.jsApiPrefix(Subjects.branchDomain(branchId)), "branch " + branchId);
    }

    /**
     * Connects a regional {@code REGION_EVT} (JetStream domain {@code region-N}) to {@code CENTER_EVT}: one source for
     * every branch of the region. Idempotent; after the first success answered from memory.
     */
    public boolean attachRegion(String domain) {
        if (regions.contains(domain)) {
            return true;
        }
        synchronized (this) {
            if (regions.contains(domain)) {
                return true;
            }
            boolean ok = attachSource(Subjects.STREAM_REGION_EVT, Subjects.jsApiPrefix(domain), "region " + domain);
            if (ok) {
                regions.add(domain);
            }
            return ok;
        }
    }

    private boolean attachSource(String stream, String api, String who) {
        var connection = nats.connection();
        if (connection.isEmpty() || !ready) {
            return false;
        }
        try {
            var jsm = connection.get().jetStreamManagement();
            var current = jsm.getStreamInfo(Subjects.STREAM_CENTER_EVT).getConfiguration();
            boolean present = current.getSources().stream()
                    .anyMatch(s -> s.getExternal() != null && api.equals(s.getExternal().getApi()));
            if (!present) {
                var updated = StreamConfiguration.builder(current)
                        .addSources(Source.builder()
                                .name(stream)
                                .external(External.builder().api(api).build())
                                .build())
                        .build();
                jsm.updateStream(updated);
                log.info("{} attached to {} (source {} api={})", who, Subjects.STREAM_CENTER_EVT, stream, api);
            }
            return true;
        } catch (IOException | JetStreamApiException e) {
            log.error("cannot attach {} to {}", who, Subjects.STREAM_CENTER_EVT, e);
            return false;
        }
    }

    private static void createIfAbsent(JetStreamManagement jsm, String name, UnaryOperator<StreamConfiguration.Builder> spec)
            throws IOException, JetStreamApiException {
        try {
            jsm.getStreamInfo(name);
        } catch (JetStreamApiException e) {
            if (e.getApiErrorCode() != STREAM_NOT_FOUND) { throw e; }
            jsm.addStream(spec.apply(StreamConfiguration.builder().name(name)).build());
            log.info("stream {} created", name);
        }
    }
}
