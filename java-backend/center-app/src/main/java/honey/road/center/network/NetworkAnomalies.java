package honey.road.center.network;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Anomalies of a branch (sect 9.5, 14; load-planning.md 6.2; design 6.4) — one function for the full overview and the
 * summary of a big network (C3c), so the two never disagree about who has a problem.
 */
final class NetworkAnomalies {
    /** Published config not applied for longer than this while online → {@code config_pending} (sect 9.5). */
    static final Duration CONFIG_PENDING_AFTER = Duration.ofMinutes(2);
    /** Сколько отделение должно провисеть в перегрузке, чтобы это стало аномалией (load-planning.md 6.2). */
    static final Duration OVERLOAD_ANOMALY_AFTER = Duration.ofMinutes(15);
    /** Red on the overview screen: the branch cannot serve or the center cannot see it. */
    static final Set<String> DANGER = Set.of("offline", "no_open_windows", "overload", "db_unavailable");

    private NetworkAnomalies() {
    }

    static boolean online(Instant lastHeartbeat, Instant now, Duration offlineAfter) {
        return Duration.between(lastHeartbeat, now).compareTo(offlineAfter) <= 0;
    }

    /** Codes in the order of the overview; monitoring alerts (design 6.4) join with their own codes. */
    static List<String> of(BranchStatus s, List<Map<String, Object>> alerts, Instant now, Duration offlineAfter) {
        boolean isOnline = online(s.lastHeartbeatAt(), now, offlineAfter);
        var anomalies = new ArrayList<String>();
        if (!isOnline) { anomalies.add("offline"); }
        if (s.oldestWaitSec() > 1800 && s.waiting() <= 3) { anomalies.add("wait_grows_without_tickets"); }
        if (s.overdue() > 5) { anomalies.add("overdue_tickets"); }
        if (s.windowsOpen() == 0 && s.waiting() > 0) { anomalies.add("no_open_windows"); }
        if (s.outboxLagSec() > 60) { anomalies.add("outbox_lag"); }
        if (isOnline && overloadedFor(s, now)) { anomalies.add("overload"); }
        // the branch is reachable but its database is not: queue numbers are the last known ones
        if (isOnline && !s.dbOk()) { anomalies.add("db_unavailable"); }
        int applied = Math.max(s.hbConfigVersion(), s.configAppliedVersion() == null ? 0 : s.configAppliedVersion());
        if (isOnline && s.configPublishedVersion() != null && applied < s.configPublishedVersion() && s.configPublishedAt() != null
                && Duration.between(s.configPublishedAt(), now).compareTo(CONFIG_PENDING_AFTER) > 0) {
            anomalies.add("config_pending");
        }
        alerts.forEach(a -> anomalies.add((String) a.get("code")));
        return anomalies;
    }

    /**
     * A branch stuck in {@code overload} longer than fifteen minutes (load-planning.md 6.2). The moment the level was
     * entered comes from the branch itself ({@code load_level_since}): a link outage would reset a count kept here
     * exactly when the branch is worst off. The branch clock may be a demo clock running at speed, so the comparison
     * uses the branch's own {@code sent_at}, falling back to the center's clock only when it is absent.
     */
    private static boolean overloadedFor(BranchStatus s, Instant now) {
        if (!"overload".equals(s.loadLevel()) || s.loadLevelSince() == null) {
            return false;
        }
        var reference = s.sentAt() == null ? now : s.sentAt();
        return Duration.between(s.loadLevelSince(), reference).compareTo(OVERLOAD_ANOMALY_AFTER) > 0;
    }

    /**
     * How bad a branch looks, for the "worst first" list: a red anomaly or a critical alert outweighs any number of
     * overdue tickets; among equals, more problems and more overdue tickets come first.
     */
    static int severity(List<String> anomalies, List<Map<String, Object>> alerts, long overdue) {
        if (anomalies.isEmpty()) { return 0; }
        int score = 0;
        for (var code : anomalies) {
            var alert = alerts.stream().filter(a -> code.equals(a.get("code"))).findFirst().orElse(null);
            score += alert != null ? alertWeight(alert.get("severity")) : DANGER.contains(code) ? 100 : 10;
        }
        return score + (int) Math.min(overdue, 9);
    }

    private static int alertWeight(@Nullable Object severity) {
        return switch (String.valueOf(severity)) {
            case "critical" -> 100;
            case "warning" -> 20;
            default -> 5;
        };
    }

    static @Nullable Instant instant(@Nullable String value) {
        if (value == null) { return null; }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
