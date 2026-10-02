package honey.road.node.messaging.nats;

import honey.road.node.config.JsonCodec;
import honey.road.node.monitoring.Lifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Is the branch leaf linked to the hub right now. The node only sees its local leaf, so the leaf-to-hub link is read
 * from the leaf monitoring endpoint {@code /leafz} every 5 s: a non-empty {@code leafs} list means the remote is up.
 * An unreachable monitor counts as "not linked".
 */
@Component
public class LeafLinkMonitor {
    private static final Logger log = LoggerFactory.getLogger(LeafLinkMonitor.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final URI leafz;
    private final JsonCodec json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private volatile Boolean linked;                             // null until the first poll

    public LeafLinkMonitor(NatsProperties props, JsonCodec json) {
        this.leafz = URI.create(props.leafMonitorUrl() + "/leafz");
        this.json = json;
    }

    public boolean linked() {
        return Boolean.TRUE.equals(linked);
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "PT5S")
    public void poll() {
        boolean now;
        try {
            var response = http.send(HttpRequest.newBuilder(leafz).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            now = response.statusCode() == 200 && !json.readTree(response.body()).path("leafs").isEmpty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            log.debug("leaf monitor {} unavailable", leafz, e);
            now = false;
        }
        var before = linked;
        linked = now;
        if (before == null || before != now) {
            if (now) {
                Lifecycle.info(log, "leaf.linked", "leaf linked to the hub");
            } else {
                Lifecycle.warn(log, "leaf.unlinked", "leaf is not linked to the hub ({})", leafz);
            }
        }
    }
}
