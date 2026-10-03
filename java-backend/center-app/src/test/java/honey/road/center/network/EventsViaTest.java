package honey.road.center.network;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code events_via} decides which stream the center sources: only a regional domain may redirect it. */
class EventsViaTest {
    @Test
    void regionalDomainsAreAccepted() {
        assertThat(EventsVia.of("region-1")).isEqualTo("region-1");
        assertThat(EventsVia.of("region-north-12")).isEqualTo("region-north-12");
    }

    @Test
    void anythingElseMeansTheBranchOwnLeaf() {
        assertThat(EventsVia.of(null)).isNull();
        assertThat(EventsVia.of("")).isNull();
        assertThat(EventsVia.of("center")).isNull();                  // never source the hub's own domain
        assertThat(EventsVia.of("branch-b01")).isNull();
        assertThat(EventsVia.of("region-1.API")).isNull();            // no way to smuggle a subject in
        assertThat(EventsVia.of("Region-1")).isNull();
        assertThat(EventsVia.of("region-" + "x".repeat(40))).isNull();
    }
}
