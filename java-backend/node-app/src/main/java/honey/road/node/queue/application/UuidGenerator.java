package honey.road.node.queue.application;

import org.springframework.stereotype.Component;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

@Component
public class UuidGenerator {
    private final SecureRandom random = new SecureRandom();

    public UUID next(Instant now) {
        // Время занимает старшие 48 бит; случайная часть не требует связи с центром.
        long high = ((now.toEpochMilli() & 0xffffffffffffL) << 16) | 0x7000L | random.nextInt(4096);
        long low = (random.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(high, low);
    }
}
