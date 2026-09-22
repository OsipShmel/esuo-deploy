package honey.road.node.planning;

import java.time.Duration;
import java.time.Instant;

/**
 * Level transitions with hysteresis (load-planning.md 4.2). Pure function of the previous state, the current
 * numbers and the clock reading passed in.
 *
 * <p>Why hysteresis at all: the level drives the booking quota, so a level that flips on every client would open
 * and close booking for a whole hour several times a minute. Clients would see slots appear and vanish, and the
 * decision journal would be unreadable — which for this system is the same as being wrong.
 *
 * <p>The rise and the fall are deliberately asymmetric:
 * <ul>
 *   <li><b>Rise</b> — the raw level must stay above the current one for {@code up_hold_min} (2 min). Short and
 *       cheap: reacting late to a crowd costs more than reacting to a blip.</li>
 *   <li><b>Fall</b> — the numbers must drop below {@code down_factor} × the threshold of the <i>current</i> level
 *       (80%) and stay there for {@code down_hold_min} (10 min). Falling on the same threshold that raised the
 *       level would put the branch right at the boundary, where it would oscillate.</li>
 * </ul>
 *
 * <p>The descent is a condition on the numbers rather than on the raw level: while it clears, the raw level may
 * bounce between {@code high} and {@code normal}, and that bouncing must not keep resetting the timer. When the
 * hold finally elapses the new level is whatever the numbers say at that moment — so a branch can fall from
 * {@code overload} straight to {@code normal} in one step.
 */
public final class LoadLevels {

    private LoadLevels() {
    }

    /**
     * @param level     level currently in force — the one the screens, the quota and the heartbeat see
     * @param since     when it was entered
     * @param upSince   when the raw level first rose above {@code level} and has stayed above since
     * @param downSince when the numbers first dropped below the relaxed threshold and have stayed below since
     */
    public record State(String level, Instant since, Instant upSince, Instant downSince) {

        public static State initial(Instant now) {
            return new State(LoadLevel.NORMAL, now, null, null);
        }

        public State normalised(Instant now) {
            return new State(LoadLevel.known(level) ? level : LoadLevel.NORMAL,
                    since == null ? now : since, upSince, downSince);
        }
    }

    /**
     * Next state.
     *
     * @param clearTimeMin minutes to clear the queue; negative when there is nothing to measure
     * @param ratioNow     inflow of the current hour against capacity; negative when unknown
     */
    public static State next(State previous, double clearTimeMin, double ratioNow,
                             PlanningConfig.Thresholds t, Instant now) {
        var state = previous == null ? State.initial(now) : previous.normalised(now);
        var raw = LoadLevel.of(clearTimeMin, ratioNow, t);
        int current = LoadLevel.rank(state.level());

        if (LoadLevel.rank(raw) > current) {
            var upSince = state.upSince() == null ? now : state.upSince();
            if (held(upSince, now, t.upHoldMin())) {
                return new State(raw, now, null, null);
            }
            // Подъём ещё не выдержан; спуск при этом заведомо не идёт, поэтому его таймер сбрасывается.
            return new State(state.level(), state.since(), upSince, null);
        }

        if (LoadLevel.rank(raw) < current && relaxed(state.level(), clearTimeMin, ratioNow, t)) {
            var downSince = state.downSince() == null ? now : state.downSince();
            if (held(downSince, now, t.downHoldMin())) {
                return new State(raw, now, null, null);
            }
            return new State(state.level(), state.since(), null, downSince);
        }

        // Уровень держится: обе попытки перехода прерваны, счётчики начинаются заново.
        return new State(state.level(), state.since(), null, null);
    }

    /**
     * Are the numbers far enough below the threshold that <i>raised</i> the current level?
     *
     * <p>Checked against the current level's own threshold, not the one just below it: leaving {@code overload}
     * means getting clearly out of overload, and where that lands — {@code high} or {@code normal} — is decided
     * afterwards by the numbers.
     */
    private static boolean relaxed(String level, double clearTimeMin, double ratioNow, PlanningConfig.Thresholds t) {
        double clearLimit = LoadLevel.OVERLOAD.equals(level) ? t.overloadClearMin() : t.highClearMin();
        double ratioLimit = LoadLevel.OVERLOAD.equals(level) ? t.overloadRatio() : t.highRatio();
        return clearTimeMin <= clearLimit * t.downFactor() && ratioNow <= ratioLimit * t.downFactor();
    }

    private static boolean held(Instant since, Instant now, double minutes) {
        if (minutes <= 0) {
            return true;
        }
        return !Duration.between(since, now).minusSeconds(Math.round(minutes * 60)).isNegative();
    }
}
