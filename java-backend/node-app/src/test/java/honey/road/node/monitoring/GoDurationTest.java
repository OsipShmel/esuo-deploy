package honey.road.node.monitoring;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Leaf RTT comes from {@code /leafz} as a Go duration string (design 6.1). */
class GoDurationTest {

    @Test
    void parsesMilliseconds() {
        assertThat(GoDuration.millis("1.2ms")).isCloseTo(1.2, within(1e-9));
    }

    @Test
    void parsesMicroseconds() {
        assertThat(GoDuration.millis("850µs")).isCloseTo(0.85, within(1e-9));      // U+00B5, as Go prints it
        assertThat(GoDuration.millis("850μs")).isCloseTo(0.85, within(1e-9));      // U+03BC
        assertThat(GoDuration.millis("850us")).isCloseTo(0.85, within(1e-9));
    }

    @Test
    void parsesCompound() {
        assertThat(GoDuration.millis("2m3.5s")).isCloseTo(123_500.0, within(1e-6));
        assertThat(GoDuration.millis("1h0m0s")).isCloseTo(3_600_000.0, within(1e-6));
        assertThat(GoDuration.millis("150ns")).isCloseTo(0.00015, within(1e-12));
        assertThat(GoDuration.millis("0s")).isZero();
    }

    @Test
    void garbageIsNull() {
        assertThat(GoDuration.millis("")).isNull();
        assertThat(GoDuration.millis("abc")).isNull();
        assertThat(GoDuration.millis("1.2")).isNull();
        assertThat(GoDuration.millis("1.2msx")).isNull();
        assertThat(GoDuration.millis(null)).isNull();
    }
}
