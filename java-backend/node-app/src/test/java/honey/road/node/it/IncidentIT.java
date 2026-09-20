package honey.road.node.it;

import honey.road.contracts.event.Actor;
import honey.road.node.queue.application.Incidents;
import honey.road.node.queue.domain.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Operator incidents (sect 9.3): creation, outbox event, closing by the admin. */
class IncidentIT extends AbstractNodeIT {
    private final Actor operator = Actor.operator(E1.toString());

    @Autowired Incidents incidents;

    @Test
    void incidentIsStoredWithReporterAndPublishedToOutbox() {
        var view = incidents.create("technical", "  Завис терминал  ", W1, null, E1, operator);

        assertThat(view.status()).isEqualTo("open");
        assertThat(view.description()).isEqualTo("Завис терминал");
        assertThat(view.reportedBy()).isEqualTo(E1);
        assertThat(view.windowId()).isEqualTo(W1);
        var payload = jdbc.queryForObject("SELECT payload::text FROM outbox WHERE subject LIKE '%incident.created'", String.class);
        assertThat(payload).contains(view.id().toString()).contains("technical").contains("Завис терминал");
    }

    @Test
    void incidentCanReferenceATicket() {
        var ticket = qr(PARCEL, "inc");
        var view = incidents.create("operational", "Клиент спорит", null, ticket, E1, operator);
        assertThat(view.ticketId()).isEqualTo(ticket);
    }

    @Test
    void invalidInputIsRejected() {
        assertThatThrownBy(() -> incidents.create("weird", "x", null, null, E1, operator)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> incidents.create("technical", "  ", null, null, E1, operator)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> incidents.create("technical", "x", UUID.randomUUID(), null, E1, operator))
                .isInstanceOf(DomainException.class).extracting(e -> ((DomainException) e).code()).isEqualTo("WINDOW_NOT_FOUND");
        assertThatThrownBy(() -> incidents.create("technical", "x", null, UUID.randomUUID(), E1, operator))
                .isInstanceOf(DomainException.class).extracting(e -> ((DomainException) e).code()).isEqualTo("TICKET_NOT_FOUND");
        assertThat(count("SELECT count(*) FROM incidents")).isZero();                 // nothing written
    }

    @Test
    void resolveIsIdempotentAndKeepsFirstResolutionTime() {
        var view = incidents.create("technical", "Сбой", null, null, E1, operator);
        var first = incidents.resolve(view.id());
        var second = incidents.resolve(view.id());

        assertThat(first.status()).isEqualTo("resolved");
        assertThat(second.resolvedAt()).isEqualTo(first.resolvedAt());
        assertThatThrownBy(() -> incidents.resolve(UUID.randomUUID())).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("NOT_FOUND");
    }
}
