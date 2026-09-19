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
 * Проверка ТЗ №7 (S12): перезапуск ноды посреди вызова. «Рестарт» — второй экземпляр приложения
 * на той же БД: всё состояние должно читаться из PostgreSQL, таймауты считаться по {@code called_at}.
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
            // Талон остался вызванным в том же окне, окно занято.
            var read = restarted.getBean(TicketQueries.class).get(view.token());
            assertThat(read.status()).isEqualTo("called");
            assertThat(read.window().id()).isEqualTo(W1);
            assertThatThrownBy(() -> restarted.getBean(CallNext.class).execute(W1, null)).isInstanceOf(DomainException.class)
                    .extracting(e -> ((DomainException) e).code()).isEqualTo("WINDOW_BUSY");
            // Неотправленные события не потеряны: outbox переживает рестарт.
            assertThat(count("SELECT count(*) FROM outbox WHERE status='pending'")).isEqualTo(outboxBefore);

            var timers = restarted.getBean(QueueTimers.class);
            // Время вышло, пока нода была недоступна: два повторных вызова, затем no_show.
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

            // Окно свободно и продолжает работу с оставшимся клиентом.
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

    /** Состарить вызов на 10 минут — как будто нода простояла выключенной. */
    private void expireCall(UUID ticket) {
        jdbc.update("UPDATE tickets SET called_at = now() - interval '10 minutes' WHERE id=?", ticket);
    }
}
