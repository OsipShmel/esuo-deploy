package honey.road.contracts.event;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * SHA-256 of the ticket token string. The node puts it into events; the center hashes the client's token the same
 * way to match SSE streams to events without storing the secret.
 */
public final class TokenHash {
    private TokenHash() {
    }

    public static String of(UUID token) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
