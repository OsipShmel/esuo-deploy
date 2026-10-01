package honey.road.node.it;

import honey.road.node.api.admin.AdminQrController;
import honey.road.node.api.publicapi.PublicTicketController;
import honey.road.node.queue.domain.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import honey.road.node.queue.application.IssueTicketCommand;
import honey.road.node.queue.domain.TicketSource;

/** Check #2: QR ticket: status, deadline by category, per-device limit; signed hall code on the public API. */
@TestPropertySource(properties = {
        "esuo.qr.secret=integration-test-qr-secret-0123456789abcdef",
        "esuo.qr.mode=local"
})
class QrIssueIT extends AbstractNodeIT {
    @Autowired PublicTicketController api;
    @Autowired AdminQrController admin;

    private PublicTicketController.QrRequest request(String code, String device) {
        return new PublicTicketController.QrRequest(code, PARCEL, "default", device);
    }

    private String invalidCode(Runnable call) {
        try {
            call.run();
        } catch (DomainException e) {
            return e.code();
        }
        return "accepted";
    }

    @Test
    void boardShowsSignedCodeWithReadyJoinLink() {
        var board = api.board();
        assertThat(board.qrCode()).matches("q1\\.b01\\.\\d+\\.[A-Za-z0-9_-]{16}");
        assertThat(board.qrModeEffective()).isEqualTo("local");
        assertThat(board.joinUrl()).isEqualTo("/b/b01/join?code=" + board.qrCode());
    }

    @Test
    void ticketIsIssuedWithBoardCodeStickerOrDemoCode() {
        var board = api.board();
        assertThat(api.qr("k-board", request(board.qrCode(), "board-phone")).status()).isEqualTo("waiting");
        assertThat(api.qr("k-sticker", request(admin.sticker().code(), "sticker-phone")).status()).isEqualTo("waiting");
        assertThat(api.qr("k-demo", request("demo:b01", "demo-phone")).status()).isEqualTo("waiting");  // demo data is on
    }

    @Test
    void forgedExpiredOrForeignCodesAreRejectedBeforeIssue() {
        var code = api.board().qrCode();
        var forged = code.substring(0, code.lastIndexOf('.') + 1) + "AAAAAAAAAAAAAAAA";
        var expired = "q1.b01.1." + code.substring(code.lastIndexOf('.') + 1);
        for (var bad : new String[]{forged, expired, code.replace("q1.b01.", "q1.b02."), "demo:b02", "q1.b01.k9.AAAAAAAAAAAAAAAA"}) {
            assertThat(invalidCode(() -> api.qr("k-" + bad, request(bad, "phone-" + bad)))).as(bad).isEqualTo("QR_CODE_INVALID");
        }
        assertThat(count("SELECT count(*) FROM tickets")).isZero();
    }

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
    void pensionerGetsShorterTargetWait() {
        var id = qr(PARCEL, "pensioner-phone", "pensioner");
        assertThat(count("SELECT EXTRACT(EPOCH FROM (due_at - issued_at))::bigint FROM tickets WHERE id=?", id)).isEqualTo(15 * 60);
    }

    @Test
    void secondActiveTicketFromSameDeviceIsRejectedUntilFirstIsClosed() {
        var first = qrView(PARCEL, "one-phone");
        assertThatThrownBy(() -> issue.execute(new IssueTicketCommand(
                TicketSource.qr, SEND, null, "default", "one-phone", null), "other-key"))
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
