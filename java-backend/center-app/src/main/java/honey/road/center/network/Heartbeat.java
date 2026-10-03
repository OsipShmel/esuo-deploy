package honey.road.center.network;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * A heartbeat as received: the raw body and the moment the center got it (the overview judges "online" by it).
 * {@code regional}: the branch is behind a regional hub ({@code events_via}) — the swarm, a mass rollout — and gets
 * the coarser metrics resolution. The body is kept as UTF-8 bytes: between two flushes the latest heartbeat of every
 * branch waits in memory, and a String with Cyrillic in it takes two bytes a character (250 000 branches: ~0.5 GB less).
 */
public record Heartbeat(String branchId, String name, String timezone, byte[] body, Instant receivedAt, boolean regional) {
    public Heartbeat(String branchId, String name, String timezone, String json, Instant receivedAt, boolean regional) {
        this(branchId, name, timezone, json.getBytes(StandardCharsets.UTF_8), receivedAt, regional);
    }

    public Heartbeat(String branchId, String name, String timezone, String json, Instant receivedAt) {
        this(branchId, name, timezone, json, receivedAt, false);
    }

    /** The body as text, for the database: decoded on each call, so callers keep it only while they write. */
    public String json() {
        return new String(body, StandardCharsets.UTF_8);
    }
}
