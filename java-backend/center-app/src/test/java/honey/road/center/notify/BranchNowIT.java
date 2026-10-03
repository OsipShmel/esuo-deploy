package honey.road.center.notify;

import honey.road.center.it.AbstractCenterIT;
import honey.road.center.network.BranchRegistry;
import honey.road.center.network.Heartbeat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Now" of a branch on demo time (sect 11.3) is its {@code sent_at} plus the real time since that heartbeat, sped up.
 * A branch behind a regional hub writes its heartbeat whole once a minute and in between only "alive" (scale design 9.2):
 * the estimate must count from the heartbeat the {@code sent_at} came with, not from the last "alive".
 */
class BranchNowIT extends AbstractCenterIT {
    @Autowired NotificationSender sender;
    @Autowired BranchRegistry registry;
    @Autowired JdbcClient jdbc;

    @Test
    void branchTimeCountsFromTheHeartbeatItsClockCameWith() {
        var contentAt = Instant.parse("2026-10-04T10:00:00Z");
        jdbc.sql("""
                INSERT INTO branches(id,name,timezone,first_seen_at,last_heartbeat_at,last_heartbeat)
                VALUES ('bn1','N','Europe/Moscow',:t,:t,'{"sent_at":"2026-10-04T12:00:00Z","demo_rate":10}'::jsonb)""")
                .param("t", Timestamp.from(contentAt)).update();
        registry.touch(List.of(new Heartbeat("bn1", "N", "Europe/Moscow", "{}", contentAt.plusSeconds(50), true)));

        assertThat(sender.branchNow("bn1", contentAt.plusSeconds(60)))
                .isEqualTo(Instant.parse("2026-10-04T12:10:00Z"));            // 60 real seconds × 10
    }
}
