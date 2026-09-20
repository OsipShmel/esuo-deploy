package honey.road.contracts.inbox;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Center → branch config ({@code center.config.branch.{b}}), sect 4.8. Applied if {@code version} is above the branch's
 * central version.
 * <ul>
 *   <li>{@code queue}: queue parameters, sect 6.9 keys in snake_case; a partial set is merged into the current central config;</li>
 *   <li>{@code bounds}: local admin override bounds, {@code {"slack_min": {"min":0,"max":15}}};</li>
 *   <li>{@code planning}: load profiles and calendar (load-planning.md 3.1), stored by the node as is;</li>
 *   <li>{@code employees}, {@code users}: branch staff (sect 13.2); missing ones are not deleted, disabled via {@code active=false}.</li>
 * </ul>
 */
public record ConfigMessage(
        int version,
        String scope,
        Map<String, Object> queue,
        Map<String, Object> bounds,
        Map<String, Object> planning,
        List<Employee> employees,
        List<User> users
) {
    /** Known {@code queue} keys (sect 6.9); the center rejects anything else. */
    public static final Set<String> QUEUE_KEYS = Set.of(
            "target_wait_min", "early_checkin_min", "grace_late_min", "late_checkin_retention_min",
            "slot_duration_min", "slot_capacity", "booking_horizon_days", "opening_time", "closing_time",
            "slack_min", "call_timeout_sec", "max_recalls", "auto_call_on_free", "scoring_strategy",
            "late_policy", "requeue_default_mode", "postpone_max_count", "postpone_max_min",
            "default_service_duration_sec", "approaching_position_lte", "approaching_eta_lte_min",
            "idle_alert_min", "long_service_min", "heuristic_min_samples", "recommend_overdue_min",
            "recommend_overdue_count", "slow_window_factor");

    public static final Set<String> ROLES = Set.of("OPERATOR", "BRANCH_ADMIN");

    public record Employee(UUID id, String fullName, Boolean active) {
    }

    /** {@code passwordHash}: bcrypt; plain passwords never travel over NATS. */
    public record User(UUID id, String login, String passwordHash, String role, UUID employeeId, Boolean active) {
    }
}
