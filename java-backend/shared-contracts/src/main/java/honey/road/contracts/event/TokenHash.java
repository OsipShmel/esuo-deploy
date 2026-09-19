package honey.road.contracts.event;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * SHA-256 от строкового представления токена талона. Нода кладёт его в события,
 * центр считает тот же хеш от токена клиента и так сопоставляет SSE-поток с событиями,
 * не храня сам секрет.
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
