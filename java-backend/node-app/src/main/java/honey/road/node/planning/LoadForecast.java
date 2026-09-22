package honey.road.node.planning;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hourly forecast for a day (load-planning.md 4.1). Pure function: no database, no ambient time, no Spring.
 *
 * <pre>
 *   поток(h)    = base_arrivals_per_day × flow_multiplier × curve_norm(h)
 *   работа(h)   = поток(h) × Σ_s Σ_c mix_s × mix_c × E[длительность | s, c, h]   — «живые» клиенты
 *               + Σ по записям на этот час E[длительность | услуга, категория, h]  — известные записи
 *   мощность(h) = planned_windows(h) × 60 × utilization_target
 *   ρ(h)        = работа(h) / мощность(h)
 * </pre>
 *
 * <p>The duration is the <b>mean</b>, not the median. Work is a sum over many clients, and for a log-normal the
 * median sits below the mean by {@code exp(sigma^2/2)}; adding medians would understate an hour by a steady few
 * percent, and the whole point of this number is to decide how many windows to open (ml-model.md 3.2).
 *
 * <p>The estimate is taken without an employee — "as anyone" — because the shift for a future hour is not known
 * and, in the worst case, is exactly what the manager is about to change.
 */
public final class LoadForecast {

    private LoadForecast() {
    }

    /** Expected duration of one service, seconds, given service code, client category and hour of day. */
    @FunctionalInterface
    public interface MeanDuration {
        double seconds(String serviceCode, String category, int hourOfDay);
    }

    /**
     * One hour of the day.
     *
     * @param arrivals     expected clients, walk-in and QR together; fractional on purpose — it is an expectation
     * @param booked       appointments already taken for this hour
     * @param workMin      minutes of window work the hour brings
     * @param capacityMin  minutes the planned windows can give
     * @param ratio        {@code workMin / capacityMin}; {@link #NO_CAPACITY} when the hour has no windows at all
     * @param level        level of this hour from {@code ratio} alone: a plan has no hysteresis to apply
     * @param bookingQuota share of slots left open for booking at that level (sect 5.1)
     */
    public record Hour(Instant hourStart, int hourOfDay, double arrivals, int booked, double workMin,
                       double capacityMin, double ratio, String level, double bookingQuota) {
    }

    /**
     * Ratio reported for an hour that has work but no planned windows. Division would give infinity, which does
     * not survive JSON, and zero would read as "everything is fine" — the one reading that is certainly wrong.
     */
    public static final double NO_CAPACITY = 99.0;

    /**
     * @param booked    appointments per local hour of the day
     * @param bookedWork minutes of work those appointments bring, per local hour
     */
    public static List<Hour> of(PlanningConfig config, PlanningConfig.Profile profile, LocalDate date, ZoneId zone,
                                MeanDuration duration, Map<Integer, Integer> booked, Map<Integer, Double> bookedWork) {
        if (profile == null || profile.closed()) {
            return List.of();
        }
        var out = new ArrayList<Hour>();
        for (int hour : profile.hours()) {
            double arrivals = config.baseArrivalsPerDay() * profile.flowMultiplier() * profile.flowShare(hour);
            double walkInWork = arrivals * expectedSeconds(profile, duration, hour) / 60.0;
            double work = walkInWork + bookedWork.getOrDefault(hour, 0.0);
            double capacity = profile.plannedWindowsAt(hour) * 60.0 * config.utilizationTarget();
            double ratio = capacity > 0 ? work / capacity : (work > 0 ? NO_CAPACITY : 0.0);
            var level = LoadLevel.of(-1, ratio, config.thresholds());
            out.add(new Hour(date.atTime(hour, 0).atZone(zone).toInstant(), hour, arrivals,
                    booked.getOrDefault(hour, 0), work, capacity, ratio, level,
                    config.levers().quotaFor(level)));
        }
        return List.copyOf(out);
    }

    /**
     * Expected duration of a client of this hour, averaged over the service and category mix of the profile.
     *
     * <p>Both mixes are already normalised to a sum of one, so this is a plain weighted average and needs no
     * further division — and a profile with an empty mix honestly yields zero work rather than a guess.
     */
    public static double expectedSeconds(PlanningConfig.Profile profile, MeanDuration duration, int hour) {
        double total = 0;
        for (var service : profile.serviceMix().entrySet()) {
            for (var category : profile.categoryMix().entrySet()) {
                total += service.getValue() * category.getValue()
                        * duration.seconds(service.getKey(), category.getKey(), hour);
            }
        }
        return total;
    }
}
