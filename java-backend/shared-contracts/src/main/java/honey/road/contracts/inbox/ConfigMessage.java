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
 *   <li>{@code model}: duration model coefficients (ml-model.md 4.4), stored by the node as is;</li>
 *   <li>{@code employees}, {@code users}: branch staff (sect 13.2); missing ones are not deleted, disabled via {@code active=false}.</li>
 * </ul>
 * <p>The model is trained offline and travels as configuration, so the branch never depends on the trainer
 * being reachable: the coefficients are already in its database (ml-model.md, the 21.09 amendment).
 * A record with no {@code model} key leaves the branch's current model untouched — that is what keeps
 * older publications and the smoke scripts working unchanged.
 */
public record ConfigMessage(
        int version,
        String scope,
        Map<String, Object> queue,
        Map<String, Object> bounds,
        Map<String, Object> planning,
        Map<String, Object> model,
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

    /**
     * Known {@code model} keys (ml-model.md 4.4). {@code a}, {@code b}, {@code e}, {@code g}, {@code h},
     * {@code w} are the additive corrections in log space; {@code counts} tells a known group with no effect
     * from an unknown one, which decides {@code basis} and {@code samples} in the call journal.
     */
    public static final Set<String> MODEL_KEYS = Set.of(
            "version", "enabled", "status", "use_window", "trained_at", "train_from", "train_to",
            "mu", "a", "b", "e", "g", "h", "w", "sigma", "sigma_default", "counts", "metrics");

    /** {@code model} keys holding a group → coefficient map. */
    public static final Set<String> MODEL_COEFFICIENT_KEYS = Set.of("a", "b", "e", "g", "h", "w", "sigma");

    /**
     * Turning the model off for one branch is a center-side branch override
     * ({@code PUT /config/branches/{b}} with {@code {"model": {"enabled": false}}}), not a local admin override.
     * <p>The local override channel ({@code PUT /admin/config}) cannot carry it: {@code ConfigRules.checkWithinBounds}
     * admits a key only when it has numeric {@code {min, max}} bounds and every leaf is a number, so a boolean
     * is impossible there by construction — the same reason {@code auto_call_on_free} has no bounds either.
     * Letting it through would mean teaching {@code bounds} a second, non-numeric shape; that is a change to the
     * shared config machinery and it is not worth it for a debug switch the center can flip with one request.
     */
    public static final Set<String> ROLES = Set.of("OPERATOR", "BRANCH_ADMIN");

    public record Employee(UUID id, String fullName, Boolean active) {
    }

    /** {@code passwordHash}: bcrypt; plain passwords never travel over NATS. */
    public record User(UUID id, String login, String passwordHash, String role, UUID employeeId, Boolean active) {
    }
}
