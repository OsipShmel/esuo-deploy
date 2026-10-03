package honey.road.swarm;

import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * Day of a virtual branch: opening hours and the hourly arrival curve (the shape of {@code planning.hourly_curve}
 * on a real node: morning ramp, lunch peak, evening peak after work). Sunday is closed.
 *
 * @param open       opening time, local
 * @param close      closing time, local
 * @param flow       share of a weekday's clients
 */
public record DayProfile(String code, LocalTime open, LocalTime close, double flow) {
    /** Relative arrivals for hours 0..23; only open hours matter. */
    private static final double[] CURVE = {
            0, 0, 0, 0, 0, 0, 0, 0,
            0.6, 0.9, 1.1, 1.1, 1.4, 1.5, 1.0, 0.9,
            1.0, 1.3, 1.5, 0.8, 0, 0, 0, 0};

    public static final DayProfile WEEKDAY = new DayProfile("normal", LocalTime.of(8, 0), LocalTime.of(20, 0), 1.0);
    public static final DayProfile SATURDAY = new DayProfile("weekend", LocalTime.of(9, 0), LocalTime.of(18, 0), 0.7);
    public static final DayProfile CLOSED = new DayProfile("closed", LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, 0);

    public static DayProfile of(DayOfWeek day) {
        return switch (day) {
            case SUNDAY -> CLOSED;
            case SATURDAY -> SATURDAY;
            default -> WEEKDAY;
        };
    }

    public boolean closed() {
        return flow == 0 || !close.isAfter(open);
    }

    /** Share of the day's clients that arrive during {@code hour} (sums to 1 over open hours). */
    public double hourShare(int hour) {
        if (closed() || hour < open.getHour() || hour >= close.getHour()) { return 0; }
        double sum = 0;
        for (int h = open.getHour(); h < close.getHour(); h++) { sum += CURVE[h]; }
        return sum == 0 ? 0 : CURVE[hour] / sum;
    }

    /** Highest hourly share: the envelope rate for thinning. */
    public double maxHourShare() {
        double max = 0;
        for (int h = 0; h < 24; h++) { max = Math.max(max, hourShare(h)); }
        return max;
    }
}
