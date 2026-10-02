package honey.road.center.notify;

import honey.road.center.it.AbstractCenterIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The provider card mixes "now" (breaker) with "ever" (counters, last error): the answer says which is which —
 * the window of the failure rate and when the last error happened.
 */
class NotificationsStatusIT extends AbstractCenterIT {
    private static final Instant ERROR_AT = Instant.parse("2026-10-02T13:05:42Z");

    @Autowired JdbcClient jdbc;

    @BeforeEach
    void log() {
        jdbc.sql("DELETE FROM notification_log").update();
        row("k1", "failed", "circuit breaker open", ERROR_AT);
        row("k1", "sent", null, ERROR_AT.plusSeconds(60));
    }

    private void row(String key, String result, String error, Instant at) {
        jdbc.sql("""
                INSERT INTO notification_log(dedup_key, branch_id, result, error, created_at)
                VALUES (:k, 'b01', :r, :e, :at)""")
                .param("k", key).param("r", result).param("e", error).param("at", Timestamp.from(at)).update();
    }

    private JsonNode status() {
        var result = mvc.get().uri("/api/v1/admin/notifications/status").header("Authorization", "Bearer " + adminToken()).exchange();
        result.assertThat().hasStatusOk();
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    @Test
    void lastErrorComesWithItsTime() {
        var s = status();

        assertThat(s.path("last_error").asString()).isEqualTo("circuit breaker open");
        assertThat(Instant.parse(s.path("last_error_at").asString())).isEqualTo(ERROR_AT);
    }

    @Test
    void failureRateNamesItsWindow() {
        assertThat(status().path("circuit_breaker").path("window_calls").asInt()).isEqualTo(10);
    }

    @Test
    void noErrorMeansNoTime() {
        jdbc.sql("DELETE FROM notification_log WHERE result = 'failed'").update();

        var s = status();
        assertThat(s.path("last_error").isNull()).isTrue();
        assertThat(s.path("last_error_at").isNull()).isTrue();
    }
}
