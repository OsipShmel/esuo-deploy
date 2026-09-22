package honey.road.node.planning;

/**
 * Branch load level (load-planning.md 4.2). Three values, ordered; the frontend branches on the exact string.
 *
 * <p>Plain constants rather than an enum: the level travels in events, heartbeat and JSON as a lowercase string,
 * and every consumer here already speaks that alphabet. An enum would buy type safety at the price of a
 * conversion at each of those five boundaries.
 */
public final class LoadLevel {
    public static final String NORMAL = "normal";
    public static final String HIGH = "high";
    public static final String OVERLOAD = "overload";

    private LoadLevel() {
    }

    /** @return 0, 1 or 2 — so levels can be compared without a switch at every call site */
    public static int rank(String level) {
        return switch (level == null ? NORMAL : level) {
            case OVERLOAD -> 2;
            case HIGH -> 1;
            default -> 0;
        };
    }

    public static boolean known(String level) {
        return NORMAL.equals(level) || HIGH.equals(level) || OVERLOAD.equals(level);
    }

    public static boolean atLeast(String level, String floor) {
        return rank(level) >= rank(floor);
    }

    /**
     * Level straight from the numbers, with no hysteresis (sect 4.2): either the queue takes too long to clear,
     * or the inflow is above what the open windows can absorb.
     *
     * @param clearTimeMin minutes to clear the queue if nobody else arrives; negative when unknown
     * @param ratio        work of the current hour against capacity; negative when unknown
     */
    public static String of(double clearTimeMin, double ratio, PlanningConfig.Thresholds t) {
        if (clearTimeMin > t.overloadClearMin() || ratio > t.overloadRatio()) {
            return OVERLOAD;
        }
        if (clearTimeMin > t.highClearMin() || ratio > t.highRatio()) {
            return HIGH;
        }
        return NORMAL;
    }
}
