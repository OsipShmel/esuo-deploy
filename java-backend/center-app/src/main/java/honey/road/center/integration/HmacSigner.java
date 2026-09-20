package honey.road.center.integration;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * MP webhook and callback signature (sect 13.2): {@code X-Signature: sha256=hex(HMAC-SHA256(secret, X-Timestamp + "." + body))}.
 */
public final class HmacSigner {
    public static final String TIMESTAMP = "X-Timestamp";
    public static final String SIGNATURE = "X-Signature";
    private static final String PREFIX = "sha256=";

    private HmacSigner() {
    }

    public static String sign(String secret, String timestamp, byte[] body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** Constant-time comparison. */
    public static boolean verify(String secret, String timestamp, byte[] body, String signature) {
        if (secret.isEmpty() || signature == null) { return false; }
        var expected = sign(secret, timestamp, body).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
