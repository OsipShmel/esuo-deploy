package honey.road.node.planning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The hourly forecast (load-planning.md 4.1).
 *
 * <p>This is the number the whole block exists for: it decides how many windows the day needs. The measured
 * effect of planning comes entirely from here — a constant 300 s understates the need by 20–45%, and the
 * shortfall turns into an hour and a half of waiting (docs/demo/ml-report.md, sect 3.2). So the arithmetic is
 * checked against hand-computed values rather than against itself.
 */
class LoadForecastTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 5);

    /** 100 клиентов в день, ровная кривая на два часа, одна услуга, одна категория, 2 окна с 09:00. */
    private static final String SECTION = """
            {"base_arrivals_per_day": 100, "utilization_target": 0.85,
             "profiles": {"normal": {"name":"Обычный день","flow_multiplier":1.0,
                          "opening_time":"09:00","closing_time":"11:00",
                          "hourly_curve":{"09":1,"10":1},"service_mix":{"parcel":1},
                          "category_mix":{"default":1},"planned_windows":{"09":2}}}}""";

    private static PlanningConfig config() {
        return PlanningConfig.of(JSON.readTree(SECTION));
    }

    /** Каждое обслуживание — ровно 5 минут, чтобы арифметику можно было проверить в уме. */
    private static final LoadForecast.MeanDuration FIVE_MINUTES = (service, category, hour) -> 300;

    private static List<LoadForecast.Hour> forecast(LoadForecast.MeanDuration duration,
                                                    Map<Integer, Integer> booked, Map<Integer, Double> bookedWork) {
        var config = config();
        return LoadForecast.of(config, config.profiles().get("normal"), DAY, MOSCOW, duration, booked, bookedWork);
    }

    @Test
    @DisplayName("работа и мощность часа считаются по формуле раздела 4.1")
    void workAndCapacity() {
        var hours = forecast(FIVE_MINUTES, Map.of(), Map.of());
        assertThat(hours).hasSize(2);

        var nine = hours.getFirst();
        // 100 клиентов × 1.0 × половина дня = 50 клиентов; 50 × 5 мин = 250 мин работы.
        assertThat(nine.arrivals()).isCloseTo(50, within(1e-9));
        assertThat(nine.workMin()).isCloseTo(250, within(1e-9));
        // 2 окна × 60 мин × 0.85 = 102 мин мощности.
        assertThat(nine.capacityMin()).isCloseTo(102, within(1e-9));
        assertThat(nine.ratio()).isCloseTo(250.0 / 102.0, within(1e-9));
        assertThat(nine.level()).isEqualTo(LoadLevel.OVERLOAD);
        assertThat(nine.bookingQuota()).as("на перегруженный час запись закрыта").isZero();
        assertThat(nine.hourStart()).isEqualTo(DAY.atTime(9, 0).atZone(MOSCOW).toInstant());
    }

    @Test
    @DisplayName("длительность обслуживания решает, сколько окон нужно: вдвое быстрее — вдвое меньше работы")
    void durationDrivesTheWork() {
        var fast = forecast((service, category, hour) -> 150, Map.of(), Map.of()).getFirst();
        assertThat(fast.workMin()).isCloseTo(125, within(1e-9));
        assertThat(fast.ratio()).isCloseTo(125.0 / 102.0, within(1e-9));
        assertThat(fast.level()).isEqualTo(LoadLevel.OVERLOAD);

        var veryFast = forecast((service, category, hour) -> 60, Map.of(), Map.of()).getFirst();
        assertThat(veryFast.workMin()).isCloseTo(50, within(1e-9));
        assertThat(veryFast.level()).isEqualTo(LoadLevel.NORMAL);
        assertThat(veryFast.bookingQuota()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("ожидание берётся по смеси услуг и категорий, а не по одной услуге")
    void mixesAreWeighted() {
        var section = """
                {"base_arrivals_per_day": 10, "utilization_target": 1.0,
                 "profiles": {"normal": {"opening_time":"09:00","closing_time":"10:00","hourly_curve":{"09":1},
                              "service_mix":{"parcel":3,"finance":1},
                              "category_mix":{"default":1,"pensioner":1},"planned_windows":{"09":1}}}}""";
        var config = PlanningConfig.of(JSON.readTree(section));
        // parcel 120 с, finance 600 с; пенсионер вдвое дольше. Доли: услуги 0,75/0,25, категории 0,5/0,5.
        LoadForecast.MeanDuration duration = (service, category, hour) -> {
            double base = "parcel".equals(service) ? 120 : 600;
            return "pensioner".equals(category) ? base * 2 : base;
        };
        var hours = LoadForecast.of(config, config.profiles().get("normal"), DAY, MOSCOW, duration, Map.of(), Map.of());

        double expectedSeconds = 0.75 * 0.5 * 120 + 0.75 * 0.5 * 240 + 0.25 * 0.5 * 600 + 0.25 * 0.5 * 1200;
        assertThat(LoadForecast.expectedSeconds(config.profiles().get("normal"), duration, 9))
                .isCloseTo(expectedSeconds, within(1e-9));
        assertThat(hours.getFirst().workMin()).isCloseTo(10 * expectedSeconds / 60.0, within(1e-9));
    }

    @Test
    @DisplayName("записи добавляют свою работу к живому потоку")
    void bookingsAddTheirOwnWork() {
        var plain = forecast(FIVE_MINUTES, Map.of(), Map.of()).getFirst();
        var withBookings = forecast(FIVE_MINUTES, Map.of(9, 4), Map.of(9, 20.0)).getFirst();

        assertThat(withBookings.booked()).isEqualTo(4);
        assertThat(withBookings.workMin()).isCloseTo(plain.workMin() + 20, within(1e-9));
        assertThat(withBookings.arrivals()).as("записи не увеличивают поток живой очереди")
                .isCloseTo(plain.arrivals(), within(1e-9));
    }

    @Test
    @DisplayName("час без окон: ρ не делится на ноль и не выдаёт «всё хорошо»")
    void anHourWithoutWindows() {
        var hours = forecast(FIVE_MINUTES, Map.of(), Map.of());
        var ten = hours.get(1);                          // planned_windows заданы только с 09:00... и держатся
        assertThat(ten.capacityMin()).as("ступенчатая функция держит 2 окна и в 10:00").isCloseTo(102, within(1e-9));

        var section = SECTION.replace("\"planned_windows\":{\"09\":2}", "\"planned_windows\":{\"09\":0}");
        var config = PlanningConfig.of(JSON.readTree(section));
        var closed = LoadForecast.of(config, config.profiles().get("normal"), DAY, MOSCOW, FIVE_MINUTES,
                Map.of(), Map.of()).getFirst();
        assertThat(closed.capacityMin()).isZero();
        assertThat(closed.ratio()).isEqualTo(LoadForecast.NO_CAPACITY);
        assertThat(closed.level()).isEqualTo(LoadLevel.OVERLOAD);
    }

    @Test
    @DisplayName("закрытый день и отсутствующий профиль дают пустой прогноз, а не ошибку")
    void closedDayHasNoForecast() {
        var config = config();
        var closed = new PlanningConfig.Profile("closed", "Выходной", true, 1.0,
                LocalTime.of(9, 0), LocalTime.of(18, 0),
                Map.of(9, 1.0), Map.of("parcel", 1.0), Map.of("default", 1.0), Map.of(9, 2));
        assertThat(LoadForecast.of(config, closed, DAY, MOSCOW, FIVE_MINUTES, Map.of(), Map.of())).isEmpty();
        assertThat(LoadForecast.of(config, null, DAY, MOSCOW, FIVE_MINUTES, Map.of(), Map.of())).isEmpty();
    }
}
