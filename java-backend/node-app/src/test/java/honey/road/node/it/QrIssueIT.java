package honey.road.node.it;

import honey.road.node.queue.domain.DomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Проверка ТЗ №2: выдача талона по QR — статус, дедлайн по категории, ограничение по устройству. */
class QrIssueIT extends AbstractNodeIT {

    @Test
    void qrTicketIsWaitingWithDeadlineFromPolicy() {
        var view = qrView(PARCEL, "phone");
        assertThat(view.status()).isEqualTo("waiting");
        assertThat(view.source()).isEqualTo("qr");
        assertThat(view.displayNumber()).startsWith("П-");
        var row = jdbc.queryForMap("SELECT EXTRACT(EPOCH FROM (due_at - issued_at)) AS wait, eligible_from = issued_at AS eligible_now "
                + "FROM tickets WHERE id=?", view.ticketId());
        assertThat(((Number) row.get("wait")).intValue()).isEqualTo(20 * 60);
        assertThat(row.get("eligible_now")).isEqualTo(true);
    }

    @Test
    void privilegedCategoryGetsShorterTargetWait() {
        var id = qr(PARCEL, "svo-phone", "svo");
        assertThat(count("SELECT EXTRACT(EPOCH FROM (due_at - issued_at))::bigint FROM tickets WHERE id=?", id)).isEqualTo(5 * 60);
    }

    @Test
    void secondActiveTicketFromSameDeviceIsRejectedUntilFirstIsClosed() {
        var first = qrView(PARCEL, "one-phone");
        assertThatThrownBy(() -> issue.execute(new honey.road.node.queue.application.IssueTicketCommand(
                honey.road.node.queue.domain.TicketSource.qr, SEND, null, "default", "one-phone", null), "other-key"))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("ACTIVE_TICKET_EXISTS");

        cancel.execute(first.token());
        assertThat(qrView(SEND, "one-phone").status()).isEqualTo("waiting");
    }

    @Test
    void numbersAreSequentialPerServiceAndDay() {
        var a = qrView(PARCEL, "n1").displayNumber();
        var b = qrView(PARCEL, "n2").displayNumber();
        var other = qrView(SEND, "n3").displayNumber();
        assertThat(a).endsWith("001");
        assertThat(b).endsWith("002");
        assertThat(other).endsWith("001");
    }

    @Test
    void ticketIsReadableByTokenAfterIssue() {
        var view = qrView(PARCEL, "reader");
        var read = queries.get(view.token());
        assertThat(read.ticketId()).isEqualTo(view.ticketId());
        assertThat(read.status()).isEqualTo("waiting");
    }
}
