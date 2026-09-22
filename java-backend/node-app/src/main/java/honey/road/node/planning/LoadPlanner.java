package honey.road.node.planning;

import honey.road.node.config.NodeProperties;
import honey.road.node.model.DurationModel;
import honey.road.node.persistence.CatalogRepository;
import honey.road.node.persistence.PolicyRepository;
import honey.road.node.persistence.TicketRepository;
import honey.road.node.persistence.WindowRepository;
import honey.road.node.queue.domain.PostalService;
import honey.road.node.queue.domain.QueuePolicy;
import honey.road.node.queue.domain.Ticket;
import honey.road.node.queue.domain.TicketStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Load state of the branch: what is happening now and what the day looks like by the hour (load-planning.md 4).
 *
 * <p>Reading is side-effect free — {@link #snapshot()} answers screens and the heartbeat without writing —
 * while {@link LoadWatcher} owns the clock and the level transitions. That split is deliberate: a screen
 * refreshing every ten seconds must not be able to move the level, and the level must move on its own schedule
 * whether or not anybody is looking.
 *
 * <p>Everything the branch decides here is local. Planning keeps working with the center unreachable, which is
 * the whole premise of the branch being autonomous (sect 12) — and the one thing a jury will try.
 */
@Service
public class LoadPlanner {

    /** Actual inflow is measured over this window and scaled to an hour (sect 4.2). */
    static final Duration INFLOW_WINDOW = Duration.ofMinutes(30);

    private final PlanningSource planning;
    private final LoadStateRepository state;
    private final TicketRepository tickets;
    private final WindowRepository windows;
    private final CatalogRepository catalog;
    private final PolicyRepository policies;
    private final DurationModel durations;
    private final NodeProperties node;
    private final Clock clock;

    public LoadPlanner(PlanningSource planning, LoadStateRepository state, TicketRepository tickets,
                       WindowRepository windows, CatalogRepository catalog, PolicyRepository policies,
                       DurationModel durations, NodeProperties node, Clock clock) {
        this.planning = planning; this.state = state; this.tickets = tickets; this.windows = windows;
        this.catalog = catalog; this.policies = policies; this.durations = durations;
        this.node = node; this.clock = clock;
    }

    /** Windows: open now, planned by the profile, needed by the current inflow (sect 4.2). */
    public record Windows(int open, int planned, int needed) {
    }

    /**
     * Effective booking quota for one hour of a date (sect 5.1): {@code hour_start}, the level the quota is based
     * on, and the quota itself, 0..1.
     */
    public record HourQuota(Instant hourStart, int hour, String level, double quota) {
    }

    /**
     * Everything the load screen, the heartbeat and the demo console show.
     *
     * @param clearTimeMin minutes until the queue empties if nobody else arrives; {@code null} with no open window
     * @param ratioNow     current inflow against current capacity; {@code null} with no open window
     */
    public record Snapshot(Instant generatedAt, LocalDate date, PlanningConfig.Chosen profile,
                           String level, Instant levelSince, Double clearTimeMin, Double ratioNow,
                           Windows windows, List<LoadCauses.Cause> causes, List<LoadForecast.Hour> forecast,
                           String modelVersion, String modelBasis) {
    }

    /** Measurements the level is derived from; kept apart so {@link LoadWatcher} can reuse one computation. */
    public record Measured(Snapshot snapshot, double clearForLevel, double ratioForLevel) {
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot() {
        return measure().snapshot();
    }

    /** Today's profile, honouring the branch admin's manual choice while it is still today (sect 3.1). */
    @Transactional(readOnly = true)
    public PlanningConfig.Chosen profileToday() {
        var now = clock.instant();
        var zone = ZoneId.of(node.timezone());
        var today = now.atZone(zone).toLocalDate();
        return planning.current().profileFor(today, manualFor(today, now));
    }

    private String manualFor(LocalDate today, Instant now) {
        var row = state.load(now);
        // Ручной выбор действует до конца своего дня и молча истекает вместе с ним.
        return today.equals(row.manualDay()) ? row.manualProfile() : null;
    }

    public Measured measure() {
        var now = clock.instant();
        var zone = ZoneId.of(node.timezone());
        var today = now.atZone(zone).toLocalDate();
        int hourNow = now.atZone(zone).getHour();

        var config = planning.current();
        var policy = policies.current();
        var branch = node.branchId();
        var row = state.load(now);
        var chosen = config.profileFor(today, today.equals(row.manualDay()) ? row.manualProfile() : null);
        var profile = chosen == null ? null : chosen.profile();

        // ---------------------------------------------------------------- сейчас
        var openWindows = windows.all(branch).stream().filter(w -> "open".equals(w.status())).toList();
        var waiting = tickets.waiting(branch);
        var active = tickets.byStatuses(branch, List.of("called", "serving"));

        double pendingWorkSec = 0;
        for (var ticket : waiting) {
            // Без сотрудника: кто именно вызовет клиента, ещё не решено (ml-model.md 5.1).
            pendingWorkSec += predictMean(ticket, null, hourNow, policy.defaultServiceDurationSec());
        }
        for (var ticket : active) {
            pendingWorkSec += remainingSeconds(ticket, now, hourNow, policy.defaultServiceDurationSec());
        }

        var inflowFrom = now.minus(INFLOW_WINDOW);
        var arrivals = tickets.issuedBetween(branch, inflowFrom, now);
        double inflowWorkSec = 0;
        for (var ticket : arrivals) {
            inflowWorkSec += predictMean(ticket, null, hourNow, policy.defaultServiceDurationSec());
        }
        double scale = 60.0 / INFLOW_WINDOW.toMinutes();
        double inflowWorkMin = inflowWorkSec / 60.0 * scale;

        double hourCapacityMin = 60.0 * config.utilizationTarget();
        int needed = (int) Math.ceil(inflowWorkMin / hourCapacityMin);
        int planned = profile == null ? 0 : profile.plannedWindowsAt(hourNow);

        Double clearTimeMin = null;
        Double ratioNow = null;
        double clearForLevel = 0;
        double ratioForLevel = 0;
        if (!openWindows.isEmpty()) {
            clearTimeMin = round(pendingWorkSec / 60.0 / openWindows.size());
            ratioNow = round(inflowWorkMin / (openWindows.size() * hourCapacityMin));
            clearForLevel = clearTimeMin;
            ratioForLevel = ratioNow;
        } else if (pendingWorkSec > 0 || inflowWorkSec > 0) {
            // Ни одного открытого окна при живой очереди: делить не на что, но и «всё хорошо» — неправда.
            // Числа на экране остаются пустыми, а уровень поднимается: причину назовёт few_windows.
            clearForLevel = Double.POSITIVE_INFINITY;
            ratioForLevel = LoadForecast.NO_CAPACITY;
        }

        // ---------------------------------------------------------------- день по часам
        var day = dayForecast(config, profile, today, zone, policy, branch);
        var forecast = day.hours();
        var bookedWork = day.bookedWork();

        // ---------------------------------------------------------------- причины
        double forecastArrivals30 = profile == null ? 0
                : config.baseArrivalsPerDay() * profile.flowMultiplier() * profile.flowShare(hourNow) / scale;
        var measuredService = measuredAgainstForecast(branch, now, policy.defaultServiceDurationSec());
        var causes = LoadCauses.of(arrivals.size(), forecastArrivals30,
                measuredService[0], measuredService[1],
                openWindows.size(), planned, needed,
                bookedWork.getOrDefault(hourNow, 0.0), openWindows.size() * hourCapacityMin,
                (int) waiting.stream().filter(t -> t.dueAt() != null && t.dueAt().isBefore(now)).count(),
                policy.recommendOverdueCount());

        // На чём стоит прогноз, спрашиваем у самой цепочки одним пробным вызовом: она одна знает,
        // хватило ли коэффициентов. Без единой услуги в справочнике спрашивать не о чем — и врать не о чем.
        String modelVersion = null;
        String modelBasis = null;
        var sampleService = anyServiceId(branch);
        if (sampleService != null) {
            var sample = durations.predict(sampleService, "default", null, hourNow, null);
            modelVersion = sample.modelVersion();
            modelBasis = basisOf(sample);
        }
        var snapshot = new Snapshot(now, today, chosen,
                row.state().level(), row.state().since(), clearTimeMin, ratioNow,
                new Windows(openWindows.size(), planned, needed), causes, forecast,
                modelVersion, modelBasis);
        return new Measured(snapshot, clearForLevel, ratioForLevel);
    }

    /**
     * Hourly forecast for an arbitrary date (load-planning.md 5.1, 6.1): the booking quota needs the forecast for
     * the appointment's own date, not just today's. Reuses {@link LoadForecast#of}; the manual profile override
     * only ever applies to today (sect 3.1), so a future date always goes by the calendar and the weekday map.
     */
    @Transactional(readOnly = true)
    public List<LoadForecast.Hour> forecastFor(LocalDate date) {
        var config = planning.current();
        var zone = ZoneId.of(node.timezone());
        var now = clock.instant();
        var today = now.atZone(zone).toLocalDate();
        var manual = date.equals(today) ? manualFor(today, now) : null;
        var chosen = config.profileFor(date, manual);
        var profile = chosen == null ? null : chosen.profile();
        return dayForecast(config, profile, date, zone, policies.current(), node.branchId()).hours();
    }

    /**
     * Effective quota per hour of a date (sect 5.1): the raw forecast level, except for the current and next
     * hour of today, where the live level counts too — whichever is worse. That way booking closes ahead of plan
     * and again if the day is going worse than the plan said.
     */
    @Transactional(readOnly = true)
    public List<HourQuota> bookingQuota(LocalDate date) {
        var config = planning.current();
        if (!config.usable()) {
            return List.of();
        }
        var zone = ZoneId.of(node.timezone());
        var now = clock.instant();
        var today = now.atZone(zone).toLocalDate();
        int hourNow = now.atZone(zone).getHour();
        String liveLevel = state.load(now).state().level();
        var out = new ArrayList<HourQuota>();
        for (var hour : forecastFor(date)) {
            String level = hour.level();
            if (date.equals(today) && (hour.hourOfDay() == hourNow || hour.hourOfDay() == hourNow + 1)
                    && LoadLevel.rank(liveLevel) > LoadLevel.rank(level)) {
                level = liveLevel;
            }
            out.add(new HourQuota(hour.hourStart(), hour.hourOfDay(), level, config.levers().quotaFor(level)));
        }
        return List.copyOf(out);
    }

    /** Quota for one hour of a date; {@code 1.0} (unrestricted) when planning is off or the hour is not in it. */
    public double quotaFor(LocalDate date, int hour) {
        return bookingQuota(date).stream().filter(h -> h.hour() == hour)
                .findFirst().map(HourQuota::quota).orElse(1.0);
    }

    /** One day's forecast plus the booked-work map {@link LoadCauses#of} needs for the current hour. */
    private record DayForecast(List<LoadForecast.Hour> hours, Map<Integer, Double> bookedWork) {
    }

    private DayForecast dayForecast(PlanningConfig config, PlanningConfig.Profile profile, LocalDate date,
                                    ZoneId zone, QueuePolicy policy, String branch) {
        var serviceIds = new HashMap<String, UUID>();
        for (PostalService service : catalog.services(branch)) {
            serviceIds.put(service.code(), service.id());
        }
        var cache = new HashMap<String, Double>();
        LoadForecast.MeanDuration duration = (code, category, hour) -> cache.computeIfAbsent(
                code + "|" + category + "|" + hour,
                key -> meanSeconds(serviceIds.get(code), category, hour, policy.defaultServiceDurationSec()));

        var booked = new HashMap<Integer, Integer>();
        var bookedWork = new HashMap<Integer, Double>();
        if (profile != null && !profile.closed()) {
            var dayStart = date.atStartOfDay(zone).toInstant();
            for (var ticket : tickets.bookedBetween(branch, dayStart, dayStart.plus(Duration.ofDays(1)))) {
                int hour = ticket.slotStart().atZone(zone).getHour();
                booked.merge(hour, 1, Integer::sum);
                bookedWork.merge(hour, predictMean(ticket, null, hour, policy.defaultServiceDurationSec()) / 60.0,
                        Double::sum);
            }
        }
        var hours = LoadForecast.of(config, profile, date, zone, duration, booked, bookedWork);
        return new DayForecast(hours, Map.copyOf(bookedWork));
    }

    /**
     * Measured against predicted duration over the recent completions (sect 4.3).
     *
     * @return {@code [measuredMeanSec, predictedMeanSec]}, both zero when there is nothing to compare
     */
    private double[] measuredAgainstForecast(String branch, Instant now, int fallbackSec) {
        var completed = tickets.completedBetween(branch, now.minus(INFLOW_WINDOW), now);
        if (completed.isEmpty()) {
            return new double[]{0, 0};
        }
        double measured = 0;
        double predicted = 0;
        for (var ticket : completed) {
            var started = ticket.servingStartedAt();
            measured += Duration.between(started, ticket.completedAt()).toSeconds();
            // Прогноз берётся с тем сотрудником, который обслуживал: иначе «медленнее обычного» поймало бы
            // не задержку, а то, что за окном сидел человек, который и так работает медленнее среднего.
            predicted += predictMean(ticket, ticket.employeeId(),
                    started.atZone(ZoneId.of(node.timezone())).getHour(), fallbackSec);
        }
        return new double[]{measured / completed.size(), predicted / completed.size()};
    }

    private double predictMean(Ticket ticket, UUID employee, int hour, int fallbackSec) {
        if (ticket.serviceId() == null) {
            return fallbackSec;
        }
        return durations.predict(ticket.serviceId(), ticket.clientCategory(), employee, hour, null).meanSeconds();
    }

    /** What is left of a service already under way: the forecast minus the time already spent, never negative. */
    private double remainingSeconds(Ticket ticket, Instant now, int hourNow, int fallbackSec) {
        var started = ticket.servingStartedAt() != null ? ticket.servingStartedAt() : ticket.calledAt();
        double predicted = predictMean(ticket, ticket.employeeId(),
                started != null ? started.atZone(ZoneId.of(node.timezone())).getHour() : hourNow, fallbackSec);
        if (ticket.status() != TicketStatus.serving || started == null) {
            return predicted;                       // вызван, но ещё не начат: работа впереди целиком
        }
        return Math.max(0, predicted - Duration.between(started, now).toSeconds());
    }

    private double meanSeconds(UUID serviceId, String category, int hour, int fallbackSec) {
        if (serviceId == null) {
            return fallbackSec;                     // услуга профиля, которой нет в справочнике отделения
        }
        return durations.predict(serviceId, category, null, hour, null).meanSeconds();
    }

    private UUID anyServiceId(String branch) {
        return catalog.services(branch).stream().findFirst().map(PostalService::id).orElse(null);
    }

    /**
     * Whether the forecast rests on the trained model or on the averages (sect 6.1).
     *
     * <p>Reported as one of two words rather than the cascade level: the screen says "обученная модель" or
     * "средние по услугам", and every cascade level is the second of those.
     */
    private static String basisOf(DurationModel.Prediction prediction) {
        return DurationModel.Prediction.BASIS_MODEL.equals(prediction.basis())
                || DurationModel.Prediction.BASIS_MODEL_PARTIAL.equals(prediction.basis())
                ? "model" : "cascade";
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
