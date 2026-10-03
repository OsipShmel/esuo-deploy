package honey.road.center.network;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Latest heartbeat of every branch until the next flush (C3b). Receiving is a map put — the NATS dispatcher never
 * waits on the database, so 4 000 heartbeats a second cannot make the center a slow consumer. A newer heartbeat of
 * the same branch replaces the older one: the registry only keeps the last state anyway, so memory is bounded by the
 * number of branches, not by the backlog.
 */
final class HeartbeatBuffer {
    record Received(String branchId, byte[] body, Instant receivedAt) {
    }

    private final int maxBranches;
    private volatile ConcurrentHashMap<String, Received> pending = new ConcurrentHashMap<>();
    private long dropped;

    HeartbeatBuffer(int maxBranches) {
        this.maxBranches = maxBranches;
    }

    /** @return false when the buffer is full of other branches and this one is dropped until the next flush */
    boolean offer(String branchId, byte[] body, Instant receivedAt) {
        var map = pending;
        if (map.size() >= maxBranches && !map.containsKey(branchId)) {
            synchronized (this) { dropped++; }
            return false;
        }
        map.put(branchId, new Received(branchId, body, receivedAt));
        return true;
    }

    /** Takes everything received since the previous drain. */
    List<Received> drain() {
        var map = pending;
        pending = new ConcurrentHashMap<>();
        return new ArrayList<>(map.values());
    }

    int size() {
        return pending.size();
    }

    synchronized long dropped() {
        return dropped;
    }
}
