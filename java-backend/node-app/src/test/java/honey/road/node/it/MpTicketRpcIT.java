package honey.road.node.it;

import honey.road.contracts.dto.TicketView;
import honey.road.node.messaging.rpc.RpcOperations;
import honey.road.node.queue.domain.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RPC {@code ticket.by_mp}: центр по {@code mp_appointment_id} получает талон вместе с токеном, чтобы собрать
 * ссылку на статус для callback в МП. В событиях и журнале токена нет — он передаётся только в ответе RPC.
 */
class MpTicketRpcIT extends AbstractNodeIT {
    @Autowired RpcOperations rpc;

    @Test
    void centerGetsTicketWithTokenByMpAppointmentId() {
        var slot = LocalDate.now(ZoneId.of("Europe/Moscow")).plusDays(1).atTime(10, 0).atZone(ZoneId.of("Europe/Moscow")).toInstant();
        var created = issue.fromMobileAppointment("mp-rpc-1", PARCEL, slot, "default");

        var reply = rpc.invoke("ticket.by_mp", "{\"mp_appointment_id\":\"mp-rpc-1\"}", null);

        assertThat(reply.ok()).isTrue();
        assertThat(reply.status()).isEqualTo(200);
        var view = (TicketView) reply.data();
        assertThat(view.ticketId()).isEqualTo(created.ticketId());
        assertThat(view.token()).isEqualTo(created.token());
        assertThat(view.status()).isEqualTo("booked");
    }

    @Test
    void unknownAppointmentAndMissingIdAreErrors() {
        assertThatThrownBy(() -> rpc.invoke("ticket.by_mp", "{\"mp_appointment_id\":\"nope\"}", null))
                .isInstanceOf(DomainException.class).extracting(e -> ((DomainException) e).code()).isEqualTo("TICKET_NOT_FOUND");
        assertThatThrownBy(() -> rpc.invoke("ticket.by_mp", "{}", null)).isInstanceOf(RuntimeException.class);
    }
}
