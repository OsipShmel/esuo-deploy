package honey.road.center.monitoring;

import honey.road.center.config.CenterProperties;
import honey.road.center.nats.CenterNats;
import honey.road.center.nats.CenterStreams;
import honey.road.contracts.event.Subjects;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static honey.road.center.network.BranchRegistry.SEEN_AT;
import static honey.road.center.network.BranchRegistry.WITH_SEEN;

/**
 * Gauges of the center that need a query (scale design 6.2): branches online and offline, the size of the metrics
 * history, the backlog of branch events not yet written. Refreshed every 15 s, not on every scrape: a scrape must not
 * count 250 000 branches each time it comes. A value that cannot be read stays at its last known one.
 */
@Component
public class CenterMeters {
    private static final Logger log = LoggerFactory.getLogger(CenterMeters.class);

    private final JdbcClient jdbc;
    private final CenterNats nats;
    private final Clock clock;
    private final Duration offlineAfter;
    private final AtomicLong online = new AtomicLong();
    private final AtomicLong offline = new AtomicLong();
    private final AtomicLong historyBytes = new AtomicLong();
    private final AtomicLong eventsPending = new AtomicLong();

    public CenterMeters(JdbcClient jdbc, CenterNats nats, CenterProperties props, Clock clock, MeterRegistry meters) {
        this.jdbc = jdbc; this.nats = nats; this.clock = clock; this.offlineAfter = props.offlineAfter();
        Gauge.builder("esuo.branches", online, AtomicLong::get).tag("state", "online")
                .description("Branches with a heartbeat within the offline threshold").register(meters);
        Gauge.builder("esuo.branches", offline, AtomicLong::get).tag("state", "offline")
                .description("Registered branches without a recent heartbeat").register(meters);
        Gauge.builder("esuo.metrics.history", historyBytes, AtomicLong::get).baseUnit("bytes")
                .description("Size of branch_metrics with its indexes").register(meters);
        Gauge.builder("esuo.events.pending", eventsPending, AtomicLong::get)
                .description("Branch events in CENTER_EVT not yet written by the ingest").register(meters);
    }

    /** Branch events not yet written, as of the last refresh. */
    public long eventsPending() {
        return eventsPending.get();
    }

    @Scheduled(fixedDelayString = "PT15S", initialDelayString = "PT5S")
    public void refresh() {
        try {
            jdbc.sql("SELECT count(*) FILTER (WHERE " + SEEN_AT + " >= :since) AS online, "
                            + "count(*) FILTER (WHERE " + SEEN_AT + " IS NULL OR " + SEEN_AT + " < :since) AS offline "
                            + "FROM " + WITH_SEEN)
                    .param("since", Timestamp.from(clock.instant().minus(offlineAfter)))
                    .query(rs -> { online.set(rs.getLong("online")); offline.set(rs.getLong("offline")); });
            historyBytes.set(jdbc.sql("""
                            SELECT coalesce(sum(pg_total_relation_size(inhrelid)), 0) FROM pg_inherits
                            WHERE inhparent = 'branch_metrics'::regclass""").query(Long.class).single());
        } catch (RuntimeException e) {
            log.debug("center gauges from the database not refreshed: {}", e.toString());
        }
        nats.connection().ifPresent(c -> {
            try {
                eventsPending.set(c.jetStreamManagement().getConsumerInfo(Subjects.STREAM_CENTER_EVT, CenterStreams.INGEST_CONSUMER)
                        .getNumPending());
            } catch (Exception e) {
                log.debug("events backlog not refreshed: {}", e.toString());
            }
        });
    }
}
