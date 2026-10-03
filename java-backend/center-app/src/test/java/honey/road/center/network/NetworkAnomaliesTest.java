package honey.road.center.network;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anomaly rules of the network overview in one place (C3c): the full overview and the summary for a big network
 * judge a branch by the same function, so they cannot disagree about who has a problem.
 */
class NetworkAnomaliesTest {
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final Duration OFFLINE_AFTER = Duration.ofSeconds(30);

    private static BranchStatus healthy(String id) {
        return new BranchStatus(id, "N", NOW.minusSeconds(5), true, 3, 0, 120, 2, 0, "normal", null, NOW, true,
                7, 7, NOW.minusSeconds(600), 7, null);
    }

    private static List<String> anomalies(BranchStatus s) {
        return NetworkAnomalies.of(s, List.of(), NOW, OFFLINE_AFTER);
    }

    @Test
    void healthyBranchHasNone() {
        assertThat(anomalies(healthy("b1"))).isEmpty();
    }

    @Test
    void eachRuleOfTheOverview() {
        var h = healthy("b1");
        assertThat(anomalies(with(h, "lastHeartbeatAt", NOW.minusSeconds(31)))).containsExactly("offline");
        assertThat(anomalies(with(h, "oldestWaitSec", 1801L))).containsExactly("wait_grows_without_tickets");
        assertThat(anomalies(with(h, "overdue", 6L))).containsExactly("overdue_tickets");
        assertThat(anomalies(with(h, "windowsOpen", 0L))).containsExactly("no_open_windows");
        assertThat(anomalies(with(h, "outboxLagSec", 61L))).containsExactly("outbox_lag");
        assertThat(anomalies(with(h, "dbOk", false))).containsExactly("db_unavailable");
    }

    @Test
    void overloadCountsOnlyAfterFifteenMinutesOfTheBranchClock() {
        var h = healthy("b1");
        var short_ = new BranchStatus("b1", "N", NOW, true, 3, 0, 0, 2, 0, "overload", NOW.minusSeconds(14 * 60), NOW,
                true, 7, 7, NOW, 7, null);
        var long_ = new BranchStatus("b1", "N", NOW, true, 3, 0, 0, 2, 0, "overload", NOW.minusSeconds(16 * 60), NOW,
                true, 7, 7, NOW, 7, null);
        assertThat(anomalies(short_)).isEmpty();
        assertThat(anomalies(long_)).containsExactly("overload");
        assertThat(anomalies(with(h, "loadLevel", "overload"))).isEmpty();    // no load_level_since: never raised
    }

    @Test
    void configPendingAfterTwoMinutesWhileOnline() {
        var pending = new BranchStatus("b1", "N", NOW, true, 0, 0, 0, 1, 0, null, null, null, true,
                6, 7, NOW.minusSeconds(121), 6, null);
        var fresh = new BranchStatus("b1", "N", NOW, true, 0, 0, 0, 1, 0, null, null, null, true,
                6, 7, NOW.minusSeconds(60), 6, null);
        assertThat(anomalies(pending)).containsExactly("config_pending");
        assertThat(anomalies(fresh)).isEmpty();
    }

    @Test
    void offlineBranchDoesNotRaiseOnlineOnlyAnomalies() {
        var lost = new BranchStatus("b1", "N", NOW.minusSeconds(600), true, 0, 0, 0, 1, 0, "overload",
                NOW.minusSeconds(3600), NOW.minusSeconds(600), false, 6, 7, NOW.minusSeconds(3600), 6, null);
        assertThat(anomalies(lost)).containsExactly("offline");
    }

    @Test
    void monitoringAlertsJoinTheAnomalies() {
        var alerts = List.<Map<String, Object>>of(Map.of("code", "sys.cpu", "severity", "warning"));
        assertThat(NetworkAnomalies.of(healthy("b1"), alerts, NOW, OFFLINE_AFTER)).containsExactly("sys.cpu");
    }

    @Test
    void severityPutsTheWorstFirst() {
        int offline = NetworkAnomalies.severity(List.of("offline"), List.of(), 0);
        int closed = NetworkAnomalies.severity(List.of("no_open_windows", "overdue_tickets"), List.of(), 9);
        int overdue = NetworkAnomalies.severity(List.of("overdue_tickets"), List.of(), 40);
        int critical = NetworkAnomalies.severity(List.of("sys.disk"), List.of(Map.of("code", "sys.disk", "severity", "critical")), 0);
        int warning = NetworkAnomalies.severity(List.of("sys.cpu"), List.of(Map.of("code", "sys.cpu", "severity", "warning")), 0);
        assertThat(closed).isGreaterThan(offline);                 // two problems beat one of the same weight
        assertThat(offline).isGreaterThan(overdue);                // a red anomaly beats any number of overdue tickets
        assertThat(critical).isGreaterThan(warning);
        assertThat(NetworkAnomalies.severity(List.of(), List.of(), 0)).isZero();
    }

    /** A copy of {@code s} with one field changed — keeps the cases above readable. */
    private static BranchStatus with(BranchStatus s, String field, Object v) {
        return new BranchStatus(s.id(), s.name(),
                field.equals("lastHeartbeatAt") ? (Instant) v : s.lastHeartbeatAt(), s.sourceAttached(),
                field.equals("waiting") ? (Long) v : s.waiting(),
                field.equals("overdue") ? (Long) v : s.overdue(),
                field.equals("oldestWaitSec") ? (Long) v : s.oldestWaitSec(),
                field.equals("windowsOpen") ? (Long) v : s.windowsOpen(),
                field.equals("outboxLagSec") ? (Long) v : s.outboxLagSec(),
                field.equals("loadLevel") ? (String) v : s.loadLevel(), s.loadLevelSince(), s.sentAt(),
                field.equals("dbOk") ? (Boolean) v : s.dbOk(),
                s.hbConfigVersion(), s.configPublishedVersion(), s.configPublishedAt(), s.configAppliedVersion(), s.eventsVia());
    }
}
