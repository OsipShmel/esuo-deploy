package honey.road.node.messaging.nats;

import honey.road.contracts.event.Subjects;
import honey.road.node.config.NodeProperties;
import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.api.DiscardPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.function.UnaryOperator;
import io.nats.client.api.External;
import io.nats.client.api.Source;

/**
 * JetStream streams in the branch domain (sect 10.4), created idempotently on every connect.
 * {@code BRANCH_EVT} buffers events while the link is down; the center pulls it as a source.
 * {@code BRANCH_LOG} does the same for important log lines published by the branch collector (design 5.3).
 */
@Component
public class BranchStreams {
    private static final Logger log = LoggerFactory.getLogger(BranchStreams.class);
    /** JetStream: stream not found. */
    static final int STREAM_NOT_FOUND = 10059;

    private final NatsGateway nats;
    private final NodeProperties node;
    private final NatsProperties props;
    private volatile boolean branchEvtReady;
    private volatile boolean branchLogReady;
    private volatile boolean branchInboxReady;

    public BranchStreams(NatsGateway nats, NodeProperties node, NatsProperties props) {
        this.nats = nats;
        this.node = node;
        this.props = props;
    }

    @PostConstruct
    void register() {
        nats.onConnected(this::ensure);
    }

    public boolean branchEvtReady() { return branchEvtReady; }

    public boolean branchInboxReady() { return branchInboxReady; }

    public boolean branchLogReady() { return branchLogReady; }

    /**
     * Own subject for BRANCH_INBOX: without it the server uses the stream name. Handy for manual inserts while
     * debugging; does not overlap with the sources.
     */
    private String inboxLocalSubject() {
        return "branch." + node.branchId() + ".inbox.local";
    }

    private static Source source(String stream, String filter, String api) {
        return Source.builder()
                .name(stream)
                .filterSubject(filter)
                .external(External.builder().api(api).build())
                .build();
    }

    void ensure(Connection c) {
        try {
            var jsm = c.jetStreamManagement();
            ensureStream(jsm, Subjects.STREAM_BRANCH_EVT, b -> b
                    .subjects(Subjects.branchEventsAll(node.branchId()))
                    .storageType(StorageType.File)
                    .maxAge(props.eventRetention())
                    .duplicateWindow(Duration.ofHours(1)));
            branchEvtReady = true;
            ensureStream(jsm, Subjects.STREAM_BRANCH_LOG, b -> b
                    .subjects(Subjects.branchLogsAll(node.branchId()))
                    .storageType(StorageType.File)
                    .maxAge(props.logRetention())
                    .maxBytes(props.logMaxBytes())
                    .discardPolicy(DiscardPolicy.Old)
                    .duplicateWindow(Duration.ofHours(1)));
            branchLogReady = true;
            // Center → branch (sect 10.4): config and MP bookings are copied from the center domain
            // and stay available to the branch when the link is down.
            var center = Subjects.jsApiPrefix(Subjects.CENTER_DOMAIN);
            ensureStream(jsm, Subjects.STREAM_BRANCH_INBOX, b -> b
                    .storageType(StorageType.File)
                    .maxAge(props.eventRetention())
                    .subjects(inboxLocalSubject())
                    .sources(
                            source(Subjects.STREAM_CONFIG, Subjects.CONFIG_GLOBAL, center),
                            source(Subjects.STREAM_CONFIG, Subjects.configBranch(node.branchId()), center),
                            source(Subjects.STREAM_MP_INBOX, Subjects.mpInbox(node.branchId()), center)));
            branchInboxReady = true;
        } catch (IOException | JetStreamApiException e) {
            branchEvtReady = false;
            branchLogReady = false;
            branchInboxReady = false;
            log.error("cannot ensure {} stream (is JetStream enabled on leaf?)", Subjects.STREAM_BRANCH_EVT, e);
        }
    }

    /** Creates the stream or updates its config. */
    public static void ensureStream(JetStreamManagement jsm, String name,
                                    UnaryOperator<StreamConfiguration.Builder> spec)
            throws IOException, JetStreamApiException {
        var config = spec.apply(StreamConfiguration.builder().name(name)).build();
        try {
            jsm.getStreamInfo(name);
            jsm.updateStream(config);
            log.debug("stream {} updated", name);
        } catch (JetStreamApiException e) {
            if (e.getApiErrorCode() != STREAM_NOT_FOUND) { throw e; }
            jsm.addStream(config);
            log.info("stream {} created", name);
        }
    }
}
