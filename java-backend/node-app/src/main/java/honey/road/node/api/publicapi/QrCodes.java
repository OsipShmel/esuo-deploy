package honey.road.node.api.publicapi;

import honey.road.node.config.NodeProperties;
import honey.road.node.queue.domain.DomainException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;

/**
 * Branch QR codes (sect 13.4). With {@code esuo.qr.secret} the codes are signed:
 * <ul>
 *   <li>screen code {@code q1.{branch}.{slot}.{sig}}, {@code slot = floor(epoch / rotation)} — accepted in its slot and
 *   the next one, so a photo of the board stops working after at most two rotations;</li>
 *   <li>sticker code {@code q1.{branch}.k{version}.{sig}} — long-lived, revoked by bumping the version;</li>
 * </ul>
 * {@code sig = base64url(HMAC-SHA256(secret, branch|slot))[:16]}. The static {@code demo:{branch}} code is accepted
 * only with demo data, so demo scenarios keep working. Slots run on real time, not on DemoClock: an accelerated demo
 * day must not rotate the board code every few seconds.
 */
@Component
public class QrCodes {
    static final String VERSION = "q1";
    static final int SIG_LENGTH = 16;
    static final int MIN_SECRET_LENGTH = 32;

    private final String branchId;
    private final boolean demoData;
    private final QrProperties props;
    private final Clock clock;

    @Autowired
    public QrCodes(NodeProperties node, QrProperties props, @Qualifier("realClock") Clock clock) {
        this(node.branchId(), node.demoData(), props, clock);
    }

    QrCodes(String branchId, boolean demoData, QrProperties props, Clock clock) {
        if (props.secret().isEmpty() && !demoData) {
            throw new IllegalStateException("Задайте QR_SECRET (не короче " + MIN_SECRET_LENGTH
                    + " символов): без демо-данных QR-код отделения должен быть подписан");
        }
        if (!props.secret().isEmpty() && props.secret().length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("QR_SECRET короче " + MIN_SECRET_LENGTH + " символов");
        }
        this.branchId = branchId;
        this.demoData = demoData;
        this.props = props;
        this.clock = clock;
    }

    public boolean signed() {
        return !props.secret().isEmpty();
    }

    /** Code for the board right now. */
    public String current() {
        return signed() ? code(Long.toString(slot())) : demoCode();
    }

    /** Long-lived code for a printed sticker. */
    public String sticker() {
        return signed() ? code(stickerPart()) : demoCode();
    }

    public int stickerKeyVersion() {
        return props.stickerKeyVersion();
    }

    public void verify(String code) {
        if (!valid(code)) {
            throw new DomainException("QR_CODE_INVALID", "QR-код недействителен");
        }
    }

    boolean valid(String code) {
        if (code == null) {
            return false;
        }
        if (code.equals(demoCode())) {
            return demoData;
        }
        if (!signed()) {
            return false;
        }
        var parts = code.split("\\.", -1);
        if (parts.length != 4 || !VERSION.equals(parts[0]) || !branchId.equals(parts[1])) {
            return false;
        }
        var part = parts[2];
        if (part.startsWith("k")) {
            if (!part.equals(stickerPart())) {
                return false;                                    // revoked or foreign sticker
            }
        } else {
            long slot;
            try {
                slot = Long.parseLong(part);
            } catch (NumberFormatException e) {
                return false;
            }
            long now = slot();
            if (slot != now && slot != now - 1) {
                return false;                                    // expired or from the future
            }
        }
        return MessageDigest.isEqual(sign(part).getBytes(StandardCharsets.US_ASCII),
                parts[3].getBytes(StandardCharsets.US_ASCII));
    }

    private long slot() {
        return Math.floorDiv(clock.instant().getEpochSecond(), props.rotation().toSeconds());
    }

    private String stickerPart() {
        return "k" + props.stickerKeyVersion();
    }

    private String code(String part) {
        return VERSION + "." + branchId + "." + part + "." + sign(part);
    }

    private String demoCode() {
        return "demo:" + branchId;
    }

    private String sign(String part) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var digest = mac.doFinal((branchId + "|" + part).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, SIG_LENGTH);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
