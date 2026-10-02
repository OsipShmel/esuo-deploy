package honey.road.center.nats;

import honey.road.contracts.event.Subjects;
import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import honey.road.center.config.CenterProperties;
import io.nats.client.api.DiscardPolicy;
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
import java.util.function.UnaryOperator;

/**
* Streams from the {@code center} domain (docs, section 10.4) and the connection of branches to {@code CENTER_EVT}.
* <p>{@code CENTER_EVT} does not have its own subjects from {@code branch}: events arrive only via sources;
* otherwise, with an active connection, each event would be received twice. A branch source is added automatically
* upon the first heartbeat ({@link #attachBranch}).
* <p>{@code CENTER_LOG} does the same for important branch log lines ({@code BRANCH_LOG} sources) and also takes the
* lines the center pulls on request ({@code center.log.pull.>}, design 5.5).
 */
@Component
public class CenterStreams {
    private static final Logger log = LoggerFactory.getLogger(CenterStreams.class);
    static final int STREAM_NOT_FOUND = 10059;

    private final CenterNats nats;
    private final CenterProperties.Logs logs;
    private volatile boolean ready;

    public CenterStreams(CenterNats nats, CenterProperties props) { this.nats = nats; this.logs = props.logs(); }

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
        var connection = nats.connection();
        if (connection.isEmpty() || !ready) {
            return false;
        }
        var api = Subjects.jsApiPrefix(Subjects.branchDomain(branchId));
        try {
            var jsm = connection.get().jetStreamManagement();
            attachSource(jsm, Subjects.STREAM_CENTER_EVT, Subjects.STREAM_BRANCH_EVT, api, branchId);
            attachSource(jsm, Subjects.STREAM_CENTER_LOG, Subjects.STREAM_BRANCH_LOG, api, branchId);
            return true;
        } catch (IOException | JetStreamApiException e) {
            log.error("cannot attach branch {} sources", branchId, e);
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
            log.info("branch {} attached to {} (source {} api={})", branchId, stream, branchStream, api);
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
