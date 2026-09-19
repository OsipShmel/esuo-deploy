package honey.road.node.messaging.nats;

import honey.road.contracts.event.Subjects;
import honey.road.node.config.NodeProperties;
import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
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
 * Стримы JetStream в домене отделения (раздел 10.4), создаются идемпотентно при каждом подключении.
 * {@code BRANCH_EVT} — буфер событий на время обрыва связи; центр забирает его через source.
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

    void ensure(Connection c) {
        try {
            var jsm = c.jetStreamManagement();
            ensureStream(jsm, Subjects.STREAM_BRANCH_EVT, b -> b
                    .subjects(Subjects.branchEventsAll(node.branchId()))
                    .storageType(StorageType.File)
                    .maxAge(props.eventRetention())
                    .duplicateWindow(Duration.ofHours(1)));
            branchEvtReady = true;
        } catch (IOException | JetStreamApiException e) {
            branchEvtReady = false;
            log.error("cannot ensure {} stream (is JetStream enabled on leaf?)", Subjects.STREAM_BRANCH_EVT, e);
        }
    }

    /** Создаёт стрим или обновляет его конфигурацию. */
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
