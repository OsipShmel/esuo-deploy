package honey.road.node.api.publicapi;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Hall QR settings ({@code esuo.qr}): where the board QR leads and how the code is signed.
 *
 * @param mode              {@code center} — join page on the center (internet), {@code local} — on the node (branch
 *                          Wi-Fi), {@code auto} — center while the leaf is linked to the hub, local otherwise
 * @param publicBaseUrl     public center address; blank => center mode is unavailable, auto always stays local
 * @param localBaseUrl      node address for phones in the hall; blank => the address the board is opened from
 * @param secret            HMAC key of the signed code; required with {@code demo-data=false}
 * @param rotation          screen code lifetime slot; a code is accepted in its slot and the next one
 * @param stickerKeyVersion version of the long-lived sticker code; bump it to revoke printed stickers
 */
@ConfigurationProperties("esuo.qr")
public record QrProperties(
        Mode mode,
        String publicBaseUrl,
        String localBaseUrl,
        String secret,
        Duration rotation,
        int stickerKeyVersion
) {
    public enum Mode { center, local, auto }

    public QrProperties {
        mode = mode == null ? Mode.auto : mode;
        publicBaseUrl = trimSlash(publicBaseUrl);
        localBaseUrl = trimSlash(localBaseUrl);
        secret = secret == null ? "" : secret.trim();
        rotation = rotation == null || rotation.toSeconds() < 10 ? Duration.ofSeconds(120) : rotation;
        stickerKeyVersion = stickerKeyVersion <= 0 ? 1 : stickerKeyVersion;
    }

    private static String trimSlash(String url) {
        return url == null ? "" : url.trim().replaceAll("/+$", "");
    }
}
