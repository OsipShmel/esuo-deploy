package honey.road.node.api.publicapi;

import honey.road.node.queue.domain.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Signed hall QR: a screen code lives two rotations, a sticker lives until its version is bumped. */
class QrCodesTest {
    private static final String SECRET = "test-qr-secret-0123456789abcdef-0123456789";
    private static final Instant SLOT_START = Instant.ofEpochSecond(120L * 15_000_000);   // exact slot boundary

    private final MutableClock clock = new MutableClock(SLOT_START);

    private QrCodes codes(String branch, boolean demoData, String secret, int stickerVersion) {
        return new QrCodes(branch, demoData, new QrProperties(null, null, null, secret, Duration.ofSeconds(120), stickerVersion), clock);
    }

    private QrCodes signed() {
        return codes("b01", false, SECRET, 1);
    }

    @Test
    void screenCodeHasDocumentedShape() {
        var code = signed().current();
        assertThat(code).matches("q1\\.b01\\.15000000\\.[A-Za-z0-9_-]{16}");
    }

    @Test
    void screenCodeIsAcceptedInItsSlotAndTheNextOne() {
        var qr = signed();
        var code = qr.current();
        clock.plus(Duration.ofSeconds(119));
        assertThat(qr.valid(code)).isTrue();
        clock.plus(Duration.ofSeconds(1));                               // next slot: board already shows a new code
        assertThat(qr.current()).isNotEqualTo(code);
        assertThat(qr.valid(code)).isTrue();
        clock.plus(Duration.ofSeconds(120));                             // two rotations later: a photo stops working
        assertThat(qr.valid(code)).isFalse();
        assertThatThrownBy(() -> qr.verify(code)).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("QR_CODE_INVALID");
    }

    @Test
    void codeFromTheFutureIsRejected() {
        var qr = signed();
        clock.plus(Duration.ofSeconds(240));
        var future = qr.current();
        clock.set(SLOT_START);
        assertThat(qr.valid(future)).isFalse();
    }

    @Test
    void tamperedOrForeignCodesAreRejected() {
        var qr = signed();
        var code = qr.current();
        var sig = code.substring(code.lastIndexOf('.') + 1);
        var flipped = (sig.charAt(0) == 'A' ? 'B' : 'A') + sig.substring(1);
        assertThat(qr.valid(code.substring(0, code.lastIndexOf('.') + 1) + flipped)).isFalse();
        assertThat(qr.valid(code.replace(".15000000.", ".14999999."))).isFalse();       // signature is per slot
        assertThat(qr.valid(codes("b02", false, SECRET, 1).current())).isFalse();      // other branch
        assertThat(qr.valid(codes("b01", false, SECRET + "x", 1).current())).isFalse(); // other key
        assertThat(qr.valid(null)).isFalse();
        assertThat(qr.valid("")).isFalse();
        assertThat(qr.valid("q1.b01.abc." + sig)).isFalse();
        assertThat(qr.valid("q2" + code.substring(2))).isFalse();
        assertThat(qr.valid(code + ".extra")).isFalse();
    }

    @Test
    void stickerLivesAcrossRotationsUntilVersionIsBumped() {
        var qr = signed();
        var sticker = qr.sticker();
        assertThat(sticker).matches("q1\\.b01\\.k1\\.[A-Za-z0-9_-]{16}");
        clock.plus(Duration.ofDays(30));
        assertThat(qr.valid(sticker)).isTrue();

        var bumped = codes("b01", false, SECRET, 2);
        assertThat(bumped.valid(sticker)).isFalse();
        assertThat(bumped.valid(bumped.sticker())).isTrue();
        assertThat(bumped.sticker()).contains(".k2.");
    }

    @Test
    void demoCodeOnlyWithDemoData() {
        assertThat(signed().valid("demo:b01")).isFalse();
        var demoSigned = codes("b01", true, SECRET, 1);
        assertThat(demoSigned.valid("demo:b01")).isTrue();             // demo scenarios keep working next to signed codes
        assertThat(demoSigned.valid("demo:b02")).isFalse();
        assertThat(demoSigned.current()).startsWith("q1.");

        var demoOnly = codes("b01", true, "", 1);
        assertThat(demoOnly.signed()).isFalse();
        assertThat(demoOnly.current()).isEqualTo("demo:b01");
        assertThat(demoOnly.sticker()).isEqualTo("demo:b01");
        assertThat(demoOnly.valid("demo:b01")).isTrue();
        assertThat(demoOnly.valid(signed().current())).isFalse();
    }

    @Test
    void secretIsRequiredWithoutDemoDataAndMustBeLongEnough() {
        assertThatThrownBy(() -> codes("b01", false, "", 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("QR_SECRET");
        assertThatThrownBy(() -> codes("b01", true, "short", 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("QR_SECRET");
    }

    @Test
    void slotsFollowRotationSetting() {
        var fast = new QrCodes("b01", false, new QrProperties(null, null, null, SECRET, Duration.ofSeconds(30), 1), clock);
        var code = fast.current();
        clock.plus(Duration.ofSeconds(60));
        assertThat(fast.valid(code)).isFalse();
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) { this.now = now; }

        void set(Instant instant) { now = instant; }

        void plus(Duration d) { now = now.plus(d); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(ZoneId zone) { return this; }

        @Override public Instant instant() { return now; }
    }
}
