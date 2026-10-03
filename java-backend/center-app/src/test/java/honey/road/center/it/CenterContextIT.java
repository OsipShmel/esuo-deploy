package honey.road.center.it;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The center starts with a database and no NATS hub, and serves its public branch list. */
class CenterContextIT extends AbstractCenterIT {

    @Test
    void contextStartsWithoutNats() throws Exception {
        var result = mvc.get().uri("/api/v1/branches").exchange();
        result.assertThat().hasStatusOk();
        assertThat(json.readTree(result.getResponse().getContentAsByteArray()).path("items").isArray()).isTrue();
    }
}
