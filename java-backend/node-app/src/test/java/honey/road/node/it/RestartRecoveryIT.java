package honey.road.node.it;

import honey.road.node.NodeApplication;
import honey.road.node.queue.application.CallNext;
import honey.road.node.queue.application.TicketQueries;
import honey.road.node.queue.domain.DomainException;
import honey.road.node.scheduler.QueueTimers;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Check #7 (S12): node restart mid-call. The "restart" is a second app instance on the same DB: all state must come
 * from PostgreSQL, timeouts from {@code called_at}.
 */
class RestartRecoveryIT extends AbstractNodeIT {

    private ConfigurableApplicationContext restartedNode() {
        return new SpringApplicationBuilder(NodeApplication.class)
                .run(
                        "--server.port=0",
                        "--esuo.demo-data=true",
                        "--esuo.branch-id=b01",
                        "--esuo.security.jwt-secret=aW50ZWdyYXRpb24tdGVzdC1zZWNyZXQtMDEyMzQ1Njc4OWFiY2RlZg==",
                        "--esuo.timers.interval=1h",
                        "--esuo.notifications.approaching.enabled=false",
                        "--esuo.nats.url=nats://127.0.0.1:1",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword());
    }

    @Test
    void calledTicketSurvivesRestartAndCallTimeoutIsCountedFromDatabase() {
        openWindow(W1, E1, PARCEL);
        var view = qrView(PARCEL, "phone");
        qr(PARCEL, "waiting-one");
        callNext.execute(W1, null);
        long outboxBefore = count("SELECT count(*) FROM outbox WHERE status='pending'");
        assertThat(outboxBefore).isPositive();

        try (var restarted = restartedNode()) {
            // The ticket is still called in the same window, the window is busy.
            var read = restarted.getBean(TicketQueries.class).get(view.token());
            assertThat(read.status()).isEqualTo("called");
            assertThat(read.window().id()).isEqualTo(W1);
            assertThatThrownBy(() -> restarted.getBean(CallNext.class).execute(W1, null)).isInstanceOf(DomainException.class)
                    .extracting(e -> ((DomainException) e).code()).isEqualTo("WINDOW_BUSY");
            // Unsent events are not lost: the outbox survives the restart.
            assertThat(count("SELECT count(*) FROM outbox WHERE status='pending'")).isEqualTo(outboxBefore);

            var timers = restarted.getBean(QueueTimers.class);
            // Time ran out while the node was down: two recalls, then no_show.
            expireCall(view.ticketId());
            timers.callTimeouts();
            expireCall(view.ticketId());
            timers.callTimeouts();
            assertThat(status(view.ticketId())).isEqualTo("called");
            assertThat(count("SELECT call_count FROM tickets WHERE id=?", view.ticketId())).isEqualTo(3);
            expireCall(view.ticketId());
            timers.callTimeouts();
            assertThat(status(view.ticketId())).isEqualTo("no_show");
            assertThat(count("SELECT count(*) FROM ticket_events WHERE ticket_id=? AND event_type='ticket.recalled'",
                    view.ticketId())).isEqualTo(2);

            // The window is free and keeps working with the remaining client.
            assertThat(restarted.getBean(CallNext.class).execute(W1, null).status()).isEqualTo("called");
        }
    }

    @Test
    void ticketWithinTimeoutIsUntouchedByTimersAfterRestart() {
        openWindow(W1, E1, PARCEL);
        var view = qrView(PARCEL, "fresh");
        callNext.execute(W1, null);
        try (var restarted = restartedNode()) {
            restarted.getBean(QueueTimers.class).callTimeouts();
            assertThat(status(view.ticketId())).isEqualTo("called");
            assertThat(count("SELECT call_count FROM tickets WHERE id=?", view.ticketId())).isEqualTo(1);
        }
    }

    /** Ages the call by 10 minutes, as if the node had been down. */
    private void expireCall(UUID ticket) {
        jdbc.update("UPDATE tickets SET called_at = now() - interval '10 minutes' WHERE id=?", ticket);
    }
}
