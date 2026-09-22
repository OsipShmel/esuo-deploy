package honey.road.node.planning;

import tools.jackson.databind.JsonNode;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The {@code planning} config section (load-planning.md 3.1) as a value: profiles, calendar and thresholds.
 *
 * <p>A pure value with no Spring, no database and no ambient time — the same shape as {@code ModelParams} for
 * the duration model, and for the same reason: the forecast has to be reproducible from its inputs alone, or
 * a demo scenario cannot be replayed three times with the same outcome.
 *
 * <p>Parsing is forgiving by design. The center validates the section before publishing
 * ({@code ConfigValidator}), so a broken value here means the branch is running against something older or
 * hand-edited; in that case planning switches off and the queue keeps working exactly as before. Silence is
 * the wrong failure mode for the hall.
 */
public final class PlanningConfig {

    /** Утилизация окна по умолчанию: окну нужны паузы между клиентами (load-planning.md 2). */
    public static final double DEFAULT_UTILIZATION = 0.85;
    private static final double DEFAULT_BASE_ARRIVALS = 100;

    /** No section published: the planner produces no forecast and the level stays {@code normal}. */
    public static final PlanningConfig NONE = new PlanningConfig();

    private final double baseArrivalsPerDay;
    private final double utilizationTarget;
    private final Map<DayOfWeek, String> weekdays;
    private final Map<LocalDate, String> calendar;
    private final Map<String, Profile> profiles;
    private final Thresholds thresholds;
    private final Levers levers;

    /** Profile of a day: how many clients come, when, for what, and how many windows are planned. */
    public record Profile(String code, String name, boolean closed, double flowMultiplier,
                          LocalTime opening, LocalTime closing,
                          Map<Integer, Double> hourlyCurve, Map<String, Double> serviceMix,
                          Map<String, Double> categoryMix, Map<Integer, Integer> plannedWindows) {

        /** Windows planned for an hour: a step function, the value holds until the next key (sect 3.1). */
        public int plannedWindowsAt(int hour) {
            int current = 0;
            for (var step : plannedWindows.entrySet()) {
                if (step.getKey() <= hour) {
                    current = step.getValue();
                }
            }
            return current;
        }

        /** Working hours of the profile, in branch-local hours. Empty for a closed day. */
        public List<Integer> hours() {
            if (closed) {
                return List.of();
            }
            var hours = new ArrayList<Integer>();
            for (int hour = opening.getHour(); hour < closingHourExclusive(); hour++) {
                hours.add(hour);
            }
            return List.copyOf(hours);
        }

        private int closingHourExclusive() {
            // 18:00 — это конец 17-го часа, а 16:30 — ещё работающий 16-й: последний час считается целиком.
            return closing.getMinute() > 0 ? closing.getHour() + 1 : closing.getHour();
        }

        /** Share of the day's flow arriving in this hour; the curve is normalised to a sum of one. */
        public double flowShare(int hour) {
            double total = hourlyCurve.values().stream().mapToDouble(Double::doubleValue).sum();
            if (total <= 0) {
                return 0;
            }
            return hourlyCurve.getOrDefault(hour, 0.0) / total;
        }
    }

    /** Level thresholds and hysteresis (sect 4.2). */
    public record Thresholds(double highRatio, double overloadRatio, double highClearMin, double overloadClearMin,
                             double downFactor, double downHoldMin, double upHoldMin) {

        public static final Thresholds DEFAULTS = new Thresholds(0.85, 1.10, 15, 30, 0.8, 10, 2);
    }

    /** Levers (sect 5): booking quota per level and the level from which walk-in clients are warned. */
    public record Levers(Map<String, Double> bookingQuota, String warnWalkinFrom) {

        public static final Levers DEFAULTS = new Levers(
                Map.of(LoadLevel.NORMAL, 1.0, LoadLevel.HIGH, 0.5, LoadLevel.OVERLOAD, 0.0), LoadLevel.HIGH);

        /** @return the share of slots left open at this level; 1.0 when the level is not listed. */
        public double quotaFor(String level) {
            var quota = bookingQuota.get(level);
            return quota == null ? 1.0 : Math.clamp(quota, 0.0, 1.0);
        }
    }

    private PlanningConfig() {
        this(DEFAULT_BASE_ARRIVALS, DEFAULT_UTILIZATION, Map.of(), Map.of(), Map.of(),
                Thresholds.DEFAULTS, Levers.DEFAULTS);
    }

    private PlanningConfig(double baseArrivalsPerDay, double utilizationTarget, Map<DayOfWeek, String> weekdays,
                           Map<LocalDate, String> calendar, Map<String, Profile> profiles,
                           Thresholds thresholds, Levers levers) {
        this.baseArrivalsPerDay = baseArrivalsPerDay;
        this.utilizationTarget = utilizationTarget;
        this.weekdays = weekdays;
        this.calendar = calendar;
        this.profiles = profiles;
        this.thresholds = thresholds;
        this.levers = levers;
    }

    /** Parses the stored section; an absent, empty or unusable one yields {@link #NONE}. */
    public static PlanningConfig of(JsonNode section) {
        if (section == null || !section.isObject() || section.isEmpty()) {
            return NONE;
        }
        var profiles = profiles(section.get("profiles"));
        if (profiles.isEmpty()) {
            // Без профилей прогнозировать нечего: пороги и рычаги сами по себе бесполезны.
            return NONE;
        }
        return new PlanningConfig(
                positive(section, "base_arrivals_per_day", DEFAULT_BASE_ARRIVALS),
                positive(section, "utilization_target", DEFAULT_UTILIZATION),
                weekdays(section.get("weekdays")),
                calendar(section.get("calendar")),
                profiles,
                thresholds(section.get("thresholds")),
                levers(section.get("levers")));
    }

    public boolean usable() {
        return this != NONE && !profiles.isEmpty();
    }

    public double baseArrivalsPerDay() {
        return baseArrivalsPerDay;
    }

    public double utilizationTarget() {
        return utilizationTarget;
    }

    public Thresholds thresholds() {
        return thresholds;
    }

    public Levers levers() {
        return levers;
    }

    public Map<String, Profile> profiles() {
        return profiles;
    }

    /** Where the profile of a day came from (sect 6.1): the center's calendar, the weekday map, or a person. */
    public record Chosen(Profile profile, String source) {

        public static final String CALENDAR = "calendar";
        public static final String WEEKDAY = "weekday";
        public static final String MANUAL = "manual";
    }

    /**
     * Profile for a date: the calendar first, then the weekday map, then {@code normal} (sect 3.1).
     *
     * @param manual code chosen by the branch admin for today, or {@code null}
     */
    public Chosen profileFor(LocalDate date, String manual) {
        if (manual != null && profiles.containsKey(manual)) {
            return new Chosen(profiles.get(manual), Chosen.MANUAL);
        }
        var byCalendar = calendar.get(date);
        if (byCalendar != null && profiles.containsKey(byCalendar)) {
            return new Chosen(profiles.get(byCalendar), Chosen.CALENDAR);
        }
        var byWeekday = weekdays.get(date.getDayOfWeek());
        if (byWeekday != null && profiles.containsKey(byWeekday)) {
            return new Chosen(profiles.get(byWeekday), Chosen.WEEKDAY);
        }
        var fallback = profiles.get("normal");
        return fallback == null ? null : new Chosen(fallback, Chosen.WEEKDAY);
    }

    // ------------------------------------------------------------------ разбор

    private static Map<String, Profile> profiles(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        var out = new LinkedHashMap<String, Profile>();
        for (var entry : node.properties()) {
            var body = entry.getValue();
            if (body == null || !body.isObject()) {
                continue;
            }
            var code = entry.getKey();
            boolean closed = body.has("closed") && body.get("closed").asBoolean();
            out.put(code, new Profile(code,
                    body.path("name").asString(code),
                    closed,
                    positive(body, "flow_multiplier", 1.0),
                    time(body, "opening_time", LocalTime.of(9, 0)),
                    time(body, "closing_time", LocalTime.of(18, 0)),
                    hourly(body.get("hourly_curve")),
                    shares(body.get("service_mix")),
                    shares(body.get("category_mix")),
                    windows(body.get("planned_windows"))));
        }
        return Map.copyOf(out);
    }

    private static Map<DayOfWeek, String> weekdays(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        var names = Map.of("mon", DayOfWeek.MONDAY, "tue", DayOfWeek.TUESDAY, "wed", DayOfWeek.WEDNESDAY,
                "thu", DayOfWeek.THURSDAY, "fri", DayOfWeek.FRIDAY, "sat", DayOfWeek.SATURDAY,
                "sun", DayOfWeek.SUNDAY);
        var out = new LinkedHashMap<DayOfWeek, String>();
        for (var entry : node.properties()) {
            var day = names.get(entry.getKey().toLowerCase());
            var profile = entry.getValue().asString(null);
            if (day != null && profile != null) {
                out.put(day, profile);
            }
        }
        return Map.copyOf(out);
    }

    private static Map<LocalDate, String> calendar(JsonNode node) {
        if (node == null || !node.isArray()) {
            return Map.of();
        }
        var out = new LinkedHashMap<LocalDate, String>();
        for (var entry : node) {
            var date = entry.path("date").asString(null);
            var profile = entry.path("profile").asString(null);
            if (date == null || profile == null) {
                continue;
            }
            try {
                out.put(LocalDate.parse(date), profile);
            } catch (RuntimeException ignored) {
                // Одна битая дата не должна ронять весь календарь: остальные записи остаются в силе.
            }
        }
        return Map.copyOf(out);
    }

    /** Hour keys are written as {@code "09"}, so they are parsed rather than used as-is. */
    private static Map<Integer, Double> hourly(JsonNode node) {
        var out = new TreeMap<Integer, Double>();
        if (node != null && node.isObject()) {
            for (var entry : node.properties()) {
                var hour = hour(entry.getKey());
                if (hour != null && entry.getValue().isNumber() && entry.getValue().asDouble() >= 0) {
                    out.put(hour, entry.getValue().asDouble());
                }
            }
        }
        return Map.copyOf(out);
    }

    private static Map<Integer, Integer> windows(JsonNode node) {
        // TreeMap: ступенчатая функция обязана обходиться по возрастанию часа, иначе шаги перепутаются.
        var out = new TreeMap<Integer, Integer>();
        if (node != null && node.isObject()) {
            for (var entry : node.properties()) {
                var hour = hour(entry.getKey());
                if (hour != null && entry.getValue().isNumber() && entry.getValue().asInt() >= 0) {
                    out.put(hour, entry.getValue().asInt());
                }
            }
        }
        return Collections.unmodifiableSortedMap(out);
    }

    private static Map<String, Double> shares(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        var out = new LinkedHashMap<String, Double>();
        double total = 0;
        for (var entry : node.properties()) {
            if (entry.getValue().isNumber() && entry.getValue().asDouble() > 0) {
                out.put(entry.getKey(), entry.getValue().asDouble());
                total += entry.getValue().asDouble();
            }
        }
        if (total <= 0) {
            return Map.of();
        }
        // Нормируем здесь, а не при каждом обращении: доли в конфигурации можно писать в любых единицах.
        var normalised = new LinkedHashMap<String, Double>();
        for (var entry : out.entrySet()) {
            normalised.put(entry.getKey(), entry.getValue() / total);
        }
        return Map.copyOf(normalised);
    }

    private static Thresholds thresholds(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Thresholds.DEFAULTS;
        }
        var d = Thresholds.DEFAULTS;
        return new Thresholds(
                positive(node, "high_ratio", d.highRatio()),
                positive(node, "overload_ratio", d.overloadRatio()),
                positive(node, "high_clear_min", d.highClearMin()),
                positive(node, "overload_clear_min", d.overloadClearMin()),
                Math.clamp(positive(node, "down_factor", d.downFactor()), 0.0, 1.0),
                Math.max(0, number(node, "down_hold_min", d.downHoldMin())),
                Math.max(0, number(node, "up_hold_min", d.upHoldMin())));
    }

    private static Levers levers(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Levers.DEFAULTS;
        }
        var quota = new LinkedHashMap<String, Double>(Levers.DEFAULTS.bookingQuota());
        var published = node.get("booking_quota");
        if (published != null && published.isObject()) {
            for (var entry : published.properties()) {
                if (entry.getValue().isNumber()) {
                    quota.put(entry.getKey(), Math.clamp(entry.getValue().asDouble(), 0.0, 1.0));
                }
            }
        }
        var warn = node.path("warn_walkin_from").asString(Levers.DEFAULTS.warnWalkinFrom());
        return new Levers(Map.copyOf(quota), LoadLevel.known(warn) ? warn : Levers.DEFAULTS.warnWalkinFrom());
    }

    private static Integer hour(String key) {
        try {
            int hour = Integer.parseInt(key.trim());
            return hour >= 0 && hour <= 23 ? hour : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalTime time(JsonNode node, String field, LocalTime fallback) {
        var value = node.path(field).asString(null);
        if (value == null) {
            return fallback;
        }
        try {
            return LocalTime.parse(value);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static double number(JsonNode node, String field, double fallback) {
        var value = node.get(field);
        return value != null && value.isNumber() ? value.asDouble() : fallback;
    }

    private static double positive(JsonNode node, String field, double fallback) {
        double value = number(node, field, fallback);
        return value > 0 ? value : fallback;
    }
}
