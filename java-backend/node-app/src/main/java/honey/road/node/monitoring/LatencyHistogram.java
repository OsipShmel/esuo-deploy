package honey.road.node.monitoring;

import org.jspecify.annotations.Nullable;

/**
 * Latency of the node's API between two heartbeats (design 6.1): fixed buckets, so p95 costs nothing to keep and is
 * exact to a bucket. Requests slower than the last bound report the slowest one seen instead of a made-up bound.
 */
public final class LatencyHistogram {
    private static final long[] BOUNDS_MS = {5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000};

    /** @param p95Ms {@code null} when there were no requests */
    public record Window(long count, long errors5xx, @Nullable Long p95Ms) {
    }

    private long[] buckets = new long[BOUNDS_MS.length + 1];
    private long errors5xx;
    private long max;

    public synchronized void record(long millis, int status) {
        int i = 0;
        while (i < BOUNDS_MS.length && millis > BOUNDS_MS[i]) {
            i++;
        }
        buckets[i]++;
        if (status >= 500) {
            errors5xx++;
        }
        max = Math.max(max, millis);
    }

    /** The window since the previous call; starts a new one. */
    public synchronized Window drain() {
        long count = 0;
        for (long b : buckets) {
            count += b;
        }
        Long p95 = null;
        if (count > 0) {
            long rank = (long) Math.ceil(count * 0.95);
            long seen = 0;
            for (int i = 0; i < buckets.length; i++) {
                seen += buckets[i];
                if (seen >= rank) {
                    p95 = i < BOUNDS_MS.length ? BOUNDS_MS[i] : max;
                    break;
                }
            }
        }
        var window = new Window(count, errors5xx, p95);
        buckets = new long[BOUNDS_MS.length + 1];
        errors5xx = 0;
        max = 0;
        return window;
    }
}
