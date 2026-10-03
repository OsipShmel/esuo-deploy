package honey.road.swarm;

/**
 * Spreads heartbeats evenly over the period: branch {@code i} beats at {@code i·period/N + k·period}. 40 000 branches
 * every 10 s are a steady 4 000 messages per second, not a burst of 40 000 every ten seconds.
 */
final class HeartbeatSchedule {
    private final int branches;
    private final long periodMs;
    private long sent;                     // heartbeats sent since the start

    HeartbeatSchedule(int branches, long periodMs) {
        this.branches = branches;
        this.periodMs = periodMs;
    }

    /** Branch indexes due by {@code elapsedMs} of real time since the start, in order; each call returns new ones. */
    void due(long elapsedMs, java.util.function.IntConsumer beat) {
        long target = (long) Math.floor((double) elapsedMs * branches / periodMs) + 1;   // index 0 beats at once
        for (; sent < target; sent++) {
            beat.accept((int) (sent % branches));
        }
    }

    long sent() { return sent; }
}
