package honey.road.node.demo;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Accelerated node time for demo scenarios (load-planning.md 7.1):
 * {@code virtual = anchorVirtual + (real − anchorReal) × rate}. Until anchors are set it runs at real time.
 */
public final class DemoClock extends Clock {

    public record Anchor(Instant real, Instant virtual, double rate) {
    }

    private final Clock real;
    private volatile Anchor anchor;

    public DemoClock(Clock real) {
        this.real = real;
        var now = real.instant();
        this.anchor = new Anchor(now, now, 1.0);
    }

    public Anchor anchor() { return anchor; }

    public void anchor(Anchor anchor) { this.anchor = anchor; }

    /** Re-anchors at the current real moment: time continues from {@code virtualNow} at {@code rate}. */
    public Anchor set(Instant virtualNow, double rate) {
        var next = new Anchor(real.instant(), virtualNow, rate);
        this.anchor = next;
        return next;
    }

    public double rate() { return anchor.rate(); }

    @Override
    public Instant instant() {
        var a = anchor;
        long elapsedMs = real.millis() - a.real().toEpochMilli();
        return a.virtual().plusMillis(Math.round(elapsedMs * a.rate()));
    }

    @Override
    public ZoneId getZone() { return ZoneOffset.UTC; }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("DemoClock is UTC only");
    }
}
