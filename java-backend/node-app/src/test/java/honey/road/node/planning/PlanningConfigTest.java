package honey.road.node.planning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Parsing the {@code planning} section (load-planning.md 3.1).
 *
 * <p>Two properties matter more than the rest and are checked hardest: that a section the branch cannot use
 * switches planning off instead of breaking the queue, and that the profile of a day is chosen in the documented
 * order. The second one decides which day the jury sees.
 */
class PlanningConfigTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String FULL = """
            {"base_arrivals_per_day": 200, "utilization_target": 0.8,
             "weekdays": {"mon":"normal","sat":"weekend","sun":"closed"},
             "calendar": [{"date":"2026-10-03","profile":"pension"}, {"date":"нет даты","profile":"pension"}],
             "profiles": {
               "normal":  {"name":"Обычный день","flow_multiplier":1.0,"opening_time":"09:00","closing_time":"18:00",
                           "hourly_curve":{"09":1,"10":3},"service_mix":{"parcel":45,"send":55},
                           "category_mix":{"default":9,"pensioner":1},"planned_windows":{"09":2,"12":3,"15":2}},
               "weekend": {"name":"Выходной","opening_time":"10:00","closing_time":"16:00",
                           "hourly_curve":{"10":1},"service_mix":{"parcel":1},"category_mix":{"default":1},
                           "planned_windows":{"10":2}},
               "pension": {"name":"Пенсионные выплаты","flow_multiplier":1.8,"hourly_curve":{"09":1},
                           "service_mix":{"finance":1},"category_mix":{"pensioner":1},"planned_windows":{"09":3}},
               "closed":  {"name":"Выходной день отделения","closed":true}},
             "thresholds": {"high_ratio":0.9,"up_hold_min":5},
             "levers": {"booking_quota":{"high":0.25},"warn_walkin_from":"overload"}}""";

    private static PlanningConfig parse(String json) {
        return PlanningConfig.of(JSON.readTree(json));
    }

    private static JsonNode node(String json) {
        return JSON.readTree(json);
    }

    // ------------------------------------------------------------------ выключено, когда нечего применять

    @Test
    @DisplayName("без секции планирование выключено, а не сломано")
    void absentSectionIsOff() {
        assertThat(PlanningConfig.of(null).usable()).isFalse();
        assertThat(PlanningConfig.of(node("{}")).usable()).isFalse();
        assertThat(PlanningConfig.of(node("[]")).usable()).isFalse();
    }

    @Test
    @DisplayName("секция без профилей бесполезна: пороги сами по себе ничего не прогнозируют")
    void sectionWithoutProfilesIsOff() {
        assertThat(parse("""
                {"base_arrivals_per_day": 100, "thresholds": {"high_ratio": 0.9}}""").usable()).isFalse();
    }

    @Test
    @DisplayName("значения по умолчанию подставляются вместо мусора, а не роняют разбор")
    void garbageFallsBackToDefaults() {
        var config = parse("""
                {"base_arrivals_per_day": -5, "utilization_target": 0,
                 "profiles": {"normal": {"opening_time":"не время","hourly_curve":{"09":1},
                              "service_mix":{"parcel":1},"category_mix":{"default":1},"planned_windows":{"09":1}}}}""");
        assertThat(config.usable()).isTrue();
        assertThat(config.baseArrivalsPerDay()).isEqualTo(100);
        assertThat(config.utilizationTarget()).isEqualTo(PlanningConfig.DEFAULT_UTILIZATION);
        assertThat(config.profiles().get("normal").opening()).hasToString("09:00");
    }

    // ------------------------------------------------------------------ выбор профиля дня

    @Test
    @DisplayName("календарь важнее дня недели, ручной выбор важнее календаря")
    void profileOrder() {
        var config = parse(FULL);
        var payday = LocalDate.of(2026, 10, 3);          // суббота, и она же в календаре как пенсионный день

        var byCalendar = config.profileFor(payday, null);
        assertThat(byCalendar.profile().code()).isEqualTo("pension");
        assertThat(byCalendar.source()).isEqualTo(PlanningConfig.Chosen.CALENDAR);

        var byWeekday = config.profileFor(LocalDate.of(2026, 10, 10), null);
        assertThat(byWeekday.profile().code()).isEqualTo("weekend");
        assertThat(byWeekday.source()).isEqualTo(PlanningConfig.Chosen.WEEKDAY);

        var manual = config.profileFor(payday, "weekend");
        assertThat(manual.profile().code()).isEqualTo("weekend");
        assertThat(manual.source()).isEqualTo(PlanningConfig.Chosen.MANUAL);
    }

    @Test
    @DisplayName("неизвестный ручной профиль игнорируется, а не обнуляет выбор")
    void unknownManualProfileIsIgnored() {
        var chosen = parse(FULL).profileFor(LocalDate.of(2026, 10, 3), "нет такого");
        assertThat(chosen.profile().code()).isEqualTo("pension");
        assertThat(chosen.source()).isEqualTo(PlanningConfig.Chosen.CALENDAR);
    }

    @Test
    @DisplayName("день без правила достаётся профилю normal")
    void fallsBackToNormal() {
        var chosen = parse(FULL).profileFor(LocalDate.of(2026, 10, 6), null);   // вторника нет в weekdays
        assertThat(chosen.profile().code()).isEqualTo("normal");
    }

    @Test
    @DisplayName("битая дата календаря выбрасывается, остальные записи в силе")
    void brokenCalendarEntryIsSkipped() {
        assertThat(parse(FULL).profileFor(LocalDate.of(2026, 10, 3), null).profile().code()).isEqualTo("pension");
    }

    // ------------------------------------------------------------------ профиль

    @Test
    @DisplayName("доли нормируются: в конфигурации их можно писать в любых единицах")
    void mixesAreNormalised() {
        var normal = parse(FULL).profiles().get("normal");
        assertThat(normal.serviceMix().get("parcel")).isCloseTo(0.45, within(1e-9));
        assertThat(normal.serviceMix().get("send")).isCloseTo(0.55, within(1e-9));
        assertThat(normal.categoryMix().values().stream().mapToDouble(Double::doubleValue).sum())
                .isCloseTo(1.0, within(1e-9));
        // Кривая часов тоже в произвольных единицах: 1 и 3 дают четверть и три четверти дня.
        assertThat(normal.flowShare(9)).isCloseTo(0.25, within(1e-9));
        assertThat(normal.flowShare(10)).isCloseTo(0.75, within(1e-9));
        assertThat(normal.flowShare(11)).isZero();
    }

    @Test
    @DisplayName("planned_windows — ступенчатая функция: значение держится до следующего ключа")
    void plannedWindowsIsAStepFunction() {
        var normal = parse(FULL).profiles().get("normal");
        assertThat(normal.plannedWindowsAt(8)).isZero();
        assertThat(normal.plannedWindowsAt(9)).isEqualTo(2);
        assertThat(normal.plannedWindowsAt(11)).isEqualTo(2);
        assertThat(normal.plannedWindowsAt(12)).isEqualTo(3);
        assertThat(normal.plannedWindowsAt(14)).isEqualTo(3);
        assertThat(normal.plannedWindowsAt(15)).isEqualTo(2);
        assertThat(normal.plannedWindowsAt(23)).isEqualTo(2);
    }

    @Test
    @DisplayName("рабочие часы профиля: закрытие в 18:00 — это конец 17-го часа")
    void workingHours() {
        var config = parse(FULL);
        assertThat(config.profiles().get("normal").hours()).containsExactly(9, 10, 11, 12, 13, 14, 15, 16, 17);
        assertThat(config.profiles().get("weekend").hours()).containsExactly(10, 11, 12, 13, 14, 15);
        assertThat(config.profiles().get("closed").hours()).isEmpty();
    }

    // ------------------------------------------------------------------ пороги и рычаги

    @Test
    @DisplayName("опубликованные пороги перекрывают умолчания по одному, а не целиком")
    void thresholdsMergeWithDefaults() {
        var t = parse(FULL).thresholds();
        assertThat(t.highRatio()).isEqualTo(0.9);                                  // опубликован
        assertThat(t.upHoldMin()).isEqualTo(5);                                    // опубликован
        assertThat(t.overloadRatio()).isEqualTo(PlanningConfig.Thresholds.DEFAULTS.overloadRatio());
        assertThat(t.downHoldMin()).isEqualTo(PlanningConfig.Thresholds.DEFAULTS.downHoldMin());
    }

    @Test
    @DisplayName("квота записи: опубликованный уровень перекрыт, остальные по умолчанию")
    void quotaMergesWithDefaults() {
        var levers = parse(FULL).levers();
        assertThat(levers.quotaFor(LoadLevel.HIGH)).isEqualTo(0.25);
        assertThat(levers.quotaFor(LoadLevel.OVERLOAD)).isZero();
        assertThat(levers.quotaFor(LoadLevel.NORMAL)).isEqualTo(1.0);
        assertThat(levers.quotaFor("неизвестный уровень")).isEqualTo(1.0);
        assertThat(levers.warnWalkinFrom()).isEqualTo(LoadLevel.OVERLOAD);
    }

    // ------------------------------------------------------------------ файл значений по умолчанию

    @Test
    @DisplayName("planning-defaults.json пригоден: иначе чистый стенд поднимется без планирования")
    void shippedDefaultsAreUsable() throws Exception {
        var stream = PlanningConfigTest.class.getResourceAsStream("/planning-defaults.json");
        assertThat(stream).as("файл лежит в infrastructure/config и подключён как ресурс модуля").isNotNull();
        // readTree(String) — тот же вызов, которым секцию читает сама нода: одна дорога, одни сюрпризы.
        var defaults = PlanningConfig.of(
                JSON.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).get("planning"));

        assertThat(defaults.usable()).isTrue();
        assertThat(defaults.profiles()).containsKeys("normal", "weekend", "pension", "closed");
        assertThat(defaults.profiles().get("closed").hours()).isEmpty();
        assertThat(defaults.profileFor(LocalDate.of(2026, 10, 5), null).profile().code())
                .as("понедельник — обычный день").isEqualTo("normal");
        assertThat(defaults.profileFor(LocalDate.of(2026, 10, 10), null).profile().code())
                .as("суббота — выходной").isEqualTo("weekend");
        // Ни один профиль не должен оказаться без окон: тогда каждый его час — перегрузка на пустом месте.
        for (var profile : defaults.profiles().values()) {
            if (profile.closed()) {
                continue;
            }
            assertThat(profile.plannedWindowsAt(profile.hours().getFirst()))
                    .as("профиль %s планирует окна с самого открытия", profile.code()).isPositive();
        }
    }

    @Test
    @DisplayName("неизвестный уровень в warn_walkin_from откатывается к умолчанию")
    void unknownWarnLevelFallsBack() {
        var levers = parse("""
                {"profiles": {"normal": {"hourly_curve":{"09":1},"service_mix":{"parcel":1},
                              "category_mix":{"default":1},"planned_windows":{"09":1}}},
                 "levers": {"warn_walkin_from": "катастрофа"}}""").levers();
        assertThat(levers.warnWalkinFrom()).isEqualTo(PlanningConfig.Levers.DEFAULTS.warnWalkinFrom());
    }
}
