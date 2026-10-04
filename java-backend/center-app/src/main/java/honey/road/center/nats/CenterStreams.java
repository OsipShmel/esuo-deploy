package honey.road.center.nats;

import honey.road.center.config.CenterProperties;
import honey.road.contracts.event.Subjects;
import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import io.nats.client.api.DiscardPolicy;
import io.nats.client.api.External;
import io.nats.client.api.RetentionPolicy;
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
* <p>{@code CENTER_LOG} does the same for important branch log lines ({@code BRANCH_LOG} sources) and also takes the
* lines the center pulls on request ({@code center.log.pull.>}, design 5.5).
 */
@Component
public class CenterStreams {
    private static final Logger log = LoggerFactory.getLogger(CenterStreams.class);
    static final int STREAM_NOT_FOUND = 10059;
    /** The durable consumer that writes {@code CENTER_EVT} into the {@code events} table. */
    public static final String INGEST_CONSUMER = "analytics-ingest";

    private final CenterNats nats;
    private final CenterProperties.Logs logs;
    private volatile boolean ready;
    /** Regions already sourced by this process: 40 000 heartbeats must not mean 40 000 stream-info calls. */
    private final Set<String> regions = ConcurrentHashMap.newKeySet();

    public CenterStreams(CenterNats nats, CenterProperties props) { this.nats = nats; this.logs = props.logs(); }

    @PostConstruct
    void register() {
        nats.onConnected(this::ensure);
    }

    public boolean ready() { return ready; }

    void ensure(Connection c) {
        try {
            var jsm = c.jetStreamManagement();
            ensureCenterEvents(jsm);
            createIfAbsent(jsm, Subjects.STREAM_CONFIG, b -> b
                    .subjects("center.config.>")
                    .storageType(StorageType.File)
                    .maxMessagesPerSubject(1));
            createIfAbsent(jsm, Subjects.STREAM_MP_INBOX, b -> b
                    .subjects("center.mp.inbox.branch.*")
                    .storageType(StorageType.File)
                    .maxAge(Duration.ofHours(24))
                    .duplicateWindow(Duration.ofHours(24)));
            createIfAbsent(jsm, Subjects.STREAM_CENTER_LOG, b -> b
                    .subjects(Subjects.CENTER_LOG_PULL_ALL)
                    .storageType(StorageType.File)
                    .maxAge(logs.retention())
                    .maxBytes(logs.maxBytes())
                    .discardPolicy(DiscardPolicy.Old)
                    .duplicateWindow(Duration.ofHours(1)));
            ready = true;
        } catch (IOException | JetStreamApiException e) {
            ready = false;
            log.error("cannot ensure center streams (is JetStream enabled on hub?)", e);
        }
    }

    /**
    * Connects the branch's {@code BRANCH_EVT} to {@code CENTER_EVT} and its {@code BRANCH_LOG} to {@code CENTER_LOG}.
    * Idempotent: adds only what is missing, so a branch attached before logs existed gets its log source too.
    * @return true if both sources exist (were added now or previously)
     */
    public synchronized boolean attachBranch(String branchId) {
        var api = Subjects.jsApiPrefix(Subjects.branchDomain(branchId));
        return withJsm("branch " + branchId, jsm -> {
            attachSource(jsm, Subjects.STREAM_CENTER_EVT, Subjects.STREAM_BRANCH_EVT, api, branchId);
            attachSource(jsm, Subjects.STREAM_CENTER_LOG, Subjects.STREAM_BRANCH_LOG, api, branchId);
        });
    }

    /**
     * Connects a regional {@code REGION_EVT} (JetStream domain {@code region-N}) to {@code CENTER_EVT}: one source for
     * every branch of the region (final plan, C1). Regional branches have no log stream of their own. Idempotent;
     * after the first success answered from memory, so 40 000 branches do not mean 40 000 stream-info calls.
     */
    public boolean attachRegion(String domain) {
        if (regions.contains(domain)) {
            return true;
        }
        synchronized (this) {
            if (regions.contains(domain)) {
                return true;
            }
            boolean ok = withJsm("region " + domain, jsm -> attachSource(jsm, Subjects.STREAM_CENTER_EVT,
                    Subjects.STREAM_REGION_EVT, Subjects.jsApiPrefix(domain), domain));
            if (ok) {
                regions.add(domain);
            }
            return ok;
        }
    }

    private interface JsmAction {
        void run(JetStreamManagement jsm) throws IOException, JetStreamApiException;
    }

    private boolean withJsm(String who, JsmAction action) {
        var connection = nats.connection();
        if (connection.isEmpty() || !ready) {
            return false;
        }
        try {
            action.run(connection.get().jetStreamManagement());
            return true;
        } catch (IOException | JetStreamApiException e) {
            log.error("cannot attach {} sources", who, e);
            return false;
        }
    }

    private static void attachSource(JetStreamManagement jsm, String stream, String branchStream, String api, String branchId)
            throws IOException, JetStreamApiException {
        var current = jsm.getStreamInfo(stream).getConfiguration();
        boolean present = current.getSources().stream()
                .anyMatch(s -> s.getExternal() != null && api.equals(s.getExternal().getApi())
                        && branchStream.equals(s.getName()));
        if (!present) {
            jsm.updateStream(StreamConfiguration.builder(current)
                    .addSources(Source.builder().name(branchStream).external(External.builder().api(api).build()).build())
                    .build());
            log.info("{} attached to {} (source {} api={})", branchId, stream, branchStream, api);
        }
    }

    /** Duplicates of {@code CENTER_EVT} are dropped by the database ({@code event_id}): minutes of ids, not an hour. */
    static final Duration EVENTS_DUPLICATE_WINDOW = Duration.ofMinutes(2);

    /**
     * {@code CENTER_EVT} is a delivery buffer, not an archive (scale design 9.3): an event stays until every reader of
     * the center has acknowledged it — the ingest, notifications, config applied, MP callbacks — and then leaves the
     * broker; the {@code events} table keeps it. 30 days bound only what nobody reads any more (a reader gone for good).
     * 30 days of read events took the hub 8 GB of disk at 250 000 branches, and a restore more memory than it had.
     * A stream of an older center, keeping everything, is brought to this when the center connects. The ingest
     * consumer comes with the stream, before {@link #ready()}: with "until read" an event that arrives while no consumer
     * exists is dropped, and sources are attached only when ready.
     */
    private static void ensureCenterEvents(JetStreamManagement jsm) throws IOException, JetStreamApiException {
        try {
            var current = jsm.getStreamInfo(Subjects.STREAM_CENTER_EVT).getConfiguration();
            if (current.getRetentionPolicy() != RetentionPolicy.Interest
                    || !EVENTS_DUPLICATE_WINDOW.equals(current.getDuplicateWindow())) {
                jsm.updateStream(StreamConfiguration.builder(current).retentionPolicy(RetentionPolicy.Interest)
                        .duplicateWindow(EVENTS_DUPLICATE_WINDOW).build());
                log.info("stream {} now keeps events until read", Subjects.STREAM_CENTER_EVT);
            }
        } catch (JetStreamApiException e) {
            if (e.getApiErrorCode() != STREAM_NOT_FOUND) { throw e; }
            jsm.addStream(StreamConfiguration.builder().name(Subjects.STREAM_CENTER_EVT)
                    .subjects("center.evt.local")               // placeholder instead of the default subject; branch.> stays untouched
                    .storageType(StorageType.File)
                    .retentionPolicy(RetentionPolicy.Interest)
                    .maxAge(Duration.ofDays(30))
                    .duplicateWindow(EVENTS_DUPLICATE_WINDOW)
                    .build());
            log.info("stream {} created", Subjects.STREAM_CENTER_EVT);
        }
        jsm.addOrUpdateConsumer(Subjects.STREAM_CENTER_EVT, ConsumerConfiguration.builder()
                .durable(INGEST_CONSUMER)
                .ackPolicy(AckPolicy.Explicit)
                .deliverPolicy(DeliverPolicy.All)
                .ackWait(Duration.ofSeconds(30))
                .maxDeliver(-1)
                .maxAckPending(10_000)
                .build());
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
