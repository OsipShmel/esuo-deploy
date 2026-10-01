package honey.road.node.api.admin;

import honey.road.node.api.publicapi.JoinLinks;
import honey.road.node.api.publicapi.QrCodes;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Printable sticker QR for the branch: a long-lived signed code, revoked by bumping {@code QR_STICKER_KEY_VERSION}. */
@RestController
@RequestMapping("/api/v1/admin/qr")
public class AdminQrController {
    private final QrCodes codes;
    private final JoinLinks links;

    public AdminQrController(QrCodes codes, JoinLinks links) {
        this.codes = codes;
        this.links = links;
    }

    /**
     * @param signed    false => demo code, the sticker works only on a demo stand
     * @param centerUrl join page on the center; null without a public center address
     * @param localUrl  join page on the node; relative when the node address is not configured
     */
    public record Sticker(String code, boolean signed, int keyVersion, String centerUrl, String localUrl) {
    }

    @GetMapping("/sticker")
    public Sticker sticker() {
        var code = codes.sticker();
        return new Sticker(code, codes.signed(), codes.stickerKeyVersion(), links.centerUrl(code), links.localUrl(code));
    }
}
