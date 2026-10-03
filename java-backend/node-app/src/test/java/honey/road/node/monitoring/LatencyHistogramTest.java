package honey.road.node.monitoring;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** HTTP latency of the node's API for the heartbeat {@code sys} block (design 6.1): fixed buckets, reset per interval. */
class LatencyHistogramTest {
    private final LatencyHistogram histogram = new LatencyHistogram();

    @Test
    void p95IsTheUpperBoundOfTheBucketHoldingThe95thValue() {
        for (int i = 0; i < 94; i++) {
            histogram.record(20, 200);
        }
        for (int i = 0; i < 6; i++) {
            histogram.record(800, 200);
        }

        var w = histogram.drain();

        assertThat(w.count()).isEqualTo(100);
        assertThat(w.p95Ms()).isEqualTo(1000L);
    }

    @Test
    void emptyWindowHasNoP95() {
        var w = histogram.drain();

        assertThat(w.count()).isZero();
        assertThat(w.p95Ms()).isNull();
    }

    @Test
    void drainResetsTheWindow() {
        histogram.record(5, 200);
        histogram.drain();

        assertThat(histogram.drain().count()).isZero();
    }

    @Test
    void counts5xxOnly() {
        histogram.record(5, 200);
        histogram.record(5, 404);
        histogram.record(5, 500);
        histogram.record(5, 503);

        assertThat(histogram.drain().errors5xx()).isEqualTo(2);
    }

    @Test
    void hugeLatencyReportsTheObservedMax() {
        histogram.record(60_000, 200);

        assertThat(histogram.drain().p95Ms()).isEqualTo(60_000L);
    }
}
