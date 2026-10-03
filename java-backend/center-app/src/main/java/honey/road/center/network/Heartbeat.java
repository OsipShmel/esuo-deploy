package honey.road.center.network;

import java.time.Instant;

/**
 * A heartbeat as received: the raw body and the moment the center got it (the overview judges "online" by it).
 * {@code regional}: the branch is behind a regional hub ({@code events_via}) — the swarm, a mass rollout — and gets
 * the coarser metrics resolution.
 */
public record Heartbeat(String branchId, String name, String timezone, String json, Instant receivedAt, boolean regional) {
    public Heartbeat(String branchId, String name, String timezone, String json, Instant receivedAt) {
        this(branchId, name, timezone, json, receivedAt, false);
    }
}
