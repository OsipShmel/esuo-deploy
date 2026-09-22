package honey.road.node.planning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Why the branch is at its level (load-planning.md 4.3).
 *
 * <p>A cause is an instruction to a person, so a wrong one is worse than none: it sends the manager to fix
 * something that is not broken. The tests below therefore spend most of their effort on the cases where a cause
 * must <i>not</i> fire.
 */
class LoadCausesTest {

    /** Всё спокойно: ни одна причина не должна срабатывать. Каждый тест двигает ровно один аргумент. */
    private static List<LoadCauses.Cause> causes(double actualArrivals, double forecastArrivals,
                                                 double actualSec, double predictedSec,
                                                 int open, int planned, int needed,
                                                 double bookedWorkMin, double capacityMin,
                                                 int overdue, int overdueLimit) {
        return LoadCauses.of(actualArrivals, forecastArrivals, actualSec, predictedSec,
                open, planned, needed, bookedWorkMin, capacityMin, overdue, overdueLimit);
    }

    private static List<LoadCauses.Cause> calm() {
        return causes(10, 10, 300, 300, 3, 3, 3, 10, 102, 0, 5);
    }

    private static List<String> codes(List<LoadCauses.Cause> causes) {
        return causes.stream().map(LoadCauses.Cause::code).toList();
    }

    @Test
    @DisplayName("спокойное отделение не называет причин")
    void calmBranchHasNoCauses() {
        assertThat(calm()).isEmpty();
    }

    @Test
    @DisplayName("поток выше прогноза — только когда он выше в 1,25 раза")
    void flowAboveForecast() {
        assertThat(codes(causes(12, 10, 300, 300, 3, 3, 3, 10, 102, 0, 5))).isEmpty();
        var fired = causes(13, 10, 300, 300, 3, 3, 3, 10, 102, 0, 5);
        assertThat(codes(fired)).containsExactly(LoadCauses.FLOW_ABOVE_FORECAST);
        assertThat(fired.getFirst().value()).isEqualTo(1.3);
        assertThat(fired.getFirst().threshold()).isEqualTo(LoadCauses.FLOW_FACTOR);
    }

    @Test
    @DisplayName("в час без прогноза потока причина не выдумывается: делить на ноль нечем")
    void zeroForecastNeverFires() {
        assertThat(codes(causes(5, 0, 300, 300, 3, 3, 3, 10, 102, 0, 5)))
                .doesNotContain(LoadCauses.FLOW_ABOVE_FORECAST);
    }

    @Test
    @DisplayName("медленное обслуживание — с запасом в 1,2 раза")
    void slowService() {
        assertThat(codes(causes(10, 10, 350, 300, 3, 3, 3, 10, 102, 0, 5))).isEmpty();
        assertThat(codes(causes(10, 10, 400, 300, 3, 3, 3, 10, 102, 0, 5)))
                .containsExactly(LoadCauses.SLOW_SERVICE);
        assertThat(codes(causes(10, 10, 400, 0, 3, 3, 3, 10, 102, 0, 5)))
                .as("без прогноза сравнивать не с чем").doesNotContain(LoadCauses.SLOW_SERVICE);
    }

    @Test
    @DisplayName("мало окон: сравнение идёт с большим из «по плану» и «нужно»")
    void fewWindows() {
        assertThat(codes(causes(10, 10, 300, 300, 3, 2, 3, 10, 102, 0, 5))).isEmpty();

        var byPlan = causes(10, 10, 300, 300, 2, 3, 1, 10, 102, 0, 5);
        assertThat(codes(byPlan)).containsExactly(LoadCauses.FEW_WINDOWS);
        assertThat(byPlan.getFirst().value()).isEqualTo(2);
        assertThat(byPlan.getFirst().threshold()).isEqualTo(3);

        var byNeed = causes(10, 10, 300, 300, 2, 1, 5, 10, 102, 0, 5);
        assertThat(byNeed.getFirst().threshold()).as("фактическая потребность выше плана").isEqualTo(5);
    }

    @Test
    @DisplayName("пик записи: больше половины мощности часа занято записями")
    void bookingPeak() {
        assertThat(codes(causes(10, 10, 300, 300, 3, 3, 3, 51, 102, 0, 5))).isEmpty();
        assertThat(codes(causes(10, 10, 300, 300, 3, 3, 3, 52, 102, 0, 5)))
                .containsExactly(LoadCauses.BOOKING_PEAK);
        assertThat(codes(causes(10, 10, 300, 300, 3, 3, 3, 52, 0, 0, 5)))
                .as("без мощности доля не определена").doesNotContain(LoadCauses.BOOKING_PEAK);
    }

    @Test
    @DisplayName("просроченные: строго больше настроенного порога")
    void overdueBacklog() {
        assertThat(codes(causes(10, 10, 300, 300, 3, 3, 3, 10, 102, 5, 5))).isEmpty();
        assertThat(codes(causes(10, 10, 300, 300, 3, 3, 3, 10, 102, 6, 5)))
                .containsExactly(LoadCauses.OVERDUE_BACKLOG);
    }

    @Test
    @DisplayName("причины накапливаются в порядке раздела 4.3, а не перетирают друг друга")
    void causesAccumulate() {
        var all = causes(20, 10, 400, 300, 1, 3, 4, 80, 102, 9, 5);
        assertThat(codes(all)).containsExactly(
                LoadCauses.FLOW_ABOVE_FORECAST, LoadCauses.SLOW_SERVICE,
                LoadCauses.FEW_WINDOWS, LoadCauses.BOOKING_PEAK, LoadCauses.OVERDUE_BACKLOG);
    }
}
