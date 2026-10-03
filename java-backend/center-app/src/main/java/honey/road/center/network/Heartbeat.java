package honey.road.center.network;

import java.time.Instant;

/** A heartbeat as received: the raw body and the moment the center got it (the overview judges "online" by it). */
public record Heartbeat(String branchId, String name, String timezone, String json, Instant receivedAt) {
}
