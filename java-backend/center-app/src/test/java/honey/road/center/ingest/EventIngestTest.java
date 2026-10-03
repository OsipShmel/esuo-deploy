package honey.road.center.ingest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The fetch options are valid for jnats: an invalid one killed the ingest thread on its first line. */
class EventIngestTest {
    @Test
    void fetchOptionsAreAccepted() {
        var options = EventIngest.fetchOptions();
        assertThat(options.getMaxMessages()).isEqualTo(EventIngest.BATCH);
        assertThat(options.getExpiresInMillis()).isEqualTo(EventIngest.WAIT_MS);
    }
}
