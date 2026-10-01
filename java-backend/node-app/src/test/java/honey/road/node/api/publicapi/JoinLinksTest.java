package honey.road.node.api.publicapi;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Where the board QR leads in each mode; {@code auto} follows the leaf link. */
class JoinLinksTest {
    private static final String CENTER = "https://esuo.example.ru";
    private final AtomicBoolean linked = new AtomicBoolean(true);

    private JoinLinks links(String mode, String publicBase, String localBase) {
        var props = new QrProperties(mode == null ? null : QrProperties.Mode.valueOf(mode), publicBase, localBase, "",
                Duration.ofSeconds(120), 1);
        return new JoinLinks("b01", props, new QrCodes("b01", true, props, Clock.systemUTC()), linked::get);
    }

    @Test
    void autoUsesCenterWhileLinkedAndLocalOtherwise() {
        var links = links(null, CENTER + "/", null);                         // auto is the default
        var qr = links.board();
        assertThat(qr.modeEffective()).isEqualTo("center");
        assertThat(qr.joinUrl()).isEqualTo(CENTER + "/b/b01/join?code=demo%3Ab01");
        assertThat(qr.code()).isEqualTo("demo:b01");

        linked.set(false);
        qr = links.board();
        assertThat(qr.modeEffective()).isEqualTo("local");
        assertThat(qr.joinUrl()).isEqualTo("/b/b01/join?code=demo%3Ab01");  // relative: the board adds its own origin
    }

    @Test
    void autoWithoutPublicAddressStaysLocal() {
        assertThat(links("auto", "", "http://192.168.1.20:8080").board().joinUrl())
                .isEqualTo("http://192.168.1.20:8080/b/b01/join?code=demo%3Ab01");
    }

    @Test
    void fixedModesIgnoreTheLink() {
        linked.set(false);
        assertThat(links("center", CENTER, null).board().modeEffective()).isEqualTo("center");
        linked.set(true);
        assertThat(links("local", CENTER, null).board().modeEffective()).isEqualTo("local");
    }

    @Test
    void centerModeNeedsPublicAddress() {
        assertThatThrownBy(() -> links("center", "", null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("QR_PUBLIC_BASE_URL");
    }

    @Test
    void stickerLinks() {
        var links = links("auto", CENTER, "http://10.0.0.5:8080");
        assertThat(links.centerUrl("demo:b01")).isEqualTo(CENTER + "/b/b01/join?code=demo%3Ab01");
        assertThat(links.localUrl("demo:b01")).isEqualTo("http://10.0.0.5:8080/b/b01/join?code=demo%3Ab01");
        assertThat(links("local", "", null).centerUrl("demo:b01")).isNull();
    }
}
