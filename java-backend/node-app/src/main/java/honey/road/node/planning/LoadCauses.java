package honey.road.node.planning;

import java.util.ArrayList;
import java.util.List;

/**
 * Why the branch is at this level (load-planning.md 4.3). Pure function.
 *
 * <p>The causes exist so the manager is told what to do rather than that something is wrong. An `overload`
 * badge on its own leaves a person guessing; "open one more window, 2 of 3" is an instruction. Every cause
 * carries the number that fired it and the threshold it crossed, because the same pair goes into the journal
 * and has to explain the decision afterwards, when nobody remembers the numbers.
 */
public final class LoadCauses {

    public static final String FLOW_ABOVE_FORECAST = "flow_above_forecast";
    public static final String SLOW_SERVICE = "slow_service";
    public static final String FEW_WINDOWS = "few_windows";
    public static final String BOOKING_PEAK = "booking_peak";
    public static final String OVERDUE_BACKLOG = "overdue_backlog";

    /** Multiples at which a deviation stops being noise (sect 4.3). */
    public static final double FLOW_FACTOR = 1.25;
    public static final double SLOW_FACTOR = 1.20;
    /** Booking is called a peak once it eats more than half the hour's capacity. */
    public static final double BOOKING_SHARE = 0.5;

    private LoadCauses() {
    }

    public record Cause(String code, double value, double threshold) {
    }

    /**
     * @param actualArrivals30    clients who came in the last 30 minutes
     * @param forecastArrivals30  how many the profile expected in the same half hour
     * @param actualMeanSec       mean measured duration over recent completions; {@code 0} when nothing to compare
     * @param predictedMeanSec    what the duration model expected for those same services
     * @param openWindows         windows accepting clients now
     * @param plannedWindows      windows the profile planned for this hour
     * @param neededWindows       windows the current inflow needs
     * @param bookedWorkMin       minutes of work booked into the current hour
     * @param capacityMin         minutes the current hour can give
     * @param overdue             tickets already past their deadline
     * @param overdueLimit        {@code recommend-overdue-count} from the queue config
     */
    public static List<Cause> of(double actualArrivals30, double forecastArrivals30,
                                 double actualMeanSec, double predictedMeanSec,
                                 int openWindows, int plannedWindows, int neededWindows,
                                 double bookedWorkMin, double capacityMin,
                                 int overdue, int overdueLimit) {
        var causes = new ArrayList<Cause>();

        // Прогноз в ноль не делится, и «в 1,25 раза больше нуля» — не факт о мире: в пустой час
        // любой единственный клиент дал бы причину, и она мигала бы вместе с ним.
        if (forecastArrivals30 > 0 && actualArrivals30 > forecastArrivals30 * FLOW_FACTOR) {
            causes.add(new Cause(FLOW_ABOVE_FORECAST, round(actualArrivals30 / forecastArrivals30), FLOW_FACTOR));
        }
        if (predictedMeanSec > 0 && actualMeanSec > predictedMeanSec * SLOW_FACTOR) {
            causes.add(new Cause(SLOW_SERVICE, round(actualMeanSec / predictedMeanSec), SLOW_FACTOR));
        }
        int wanted = Math.max(plannedWindows, neededWindows);
        if (openWindows < wanted) {
            causes.add(new Cause(FEW_WINDOWS, openWindows, wanted));
        }
        if (capacityMin > 0 && bookedWorkMin > capacityMin * BOOKING_SHARE) {
            causes.add(new Cause(BOOKING_PEAK, round(bookedWorkMin / capacityMin), BOOKING_SHARE));
        }
        if (overdue > overdueLimit) {
            causes.add(new Cause(OVERDUE_BACKLOG, overdue, overdueLimit));
        }
        return List.copyOf(causes);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
