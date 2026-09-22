package honey.road.node.planning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hysteresis of the load level (load-planning.md 4.2).
 *
 * <p>What is really under test is that the level cannot flutter. The level opens and closes booking for whole
 * hours, so a level that follows the numbers instantly would make slots appear and vanish for clients several
 * times a minute — and would fill the decision journal with noise nobody can review afterwards.
 */
class LoadLevelsTest {
    private static final PlanningConfig.Thresholds T = PlanningConfig.Thresholds.DEFAULTS;
    private static final Instant T0 = Instant.parse("2026-10-03T06:00:00Z");

    /** Значения, заведомо дающие сырой уровень: 0,5 — спокойно, 1,0 — высокая, 2,0 — перегрузка. */
    private static final double CALM = 0.5;
    private static final double BUSY = 1.0;
    private static final double FLOOD = 2.0;

    private static Instant at(int minutes) {
        return T0.plus(Duration.ofMinutes(minutes));
    }

    /** Ratio as the only signal: clear time stays at zero so each test moves exactly one lever. */
    private static LoadLevels.State step(LoadLevels.State state, double ratio, int minute) {
        return LoadLevels.next(state, 0, ratio, T, at(minute));
    }

    // ------------------------------------------------------------------ подъём

    @Test
    @DisplayName("подъём не срабатывает раньше up_hold_min")
    void riseWaitsForTheHold() {
        var state = LoadLevels.State.initial(T0);

        state = step(state, BUSY, 0);
        assertThat(state.level()).isEqualTo(LoadLevel.NORMAL);
        assertThat(state.upSince()).isEqualTo(T0);

        state = step(state, BUSY, 1);
        assertThat(state.level()).isEqualTo(LoadLevel.NORMAL);

        state = step(state, BUSY, 2);                     // up_hold_min = 2
        assertThat(state.level()).isEqualTo(LoadLevel.HIGH);
        assertThat(state.since()).isEqualTo(at(2));
        assertThat(state.upSince()).isNull();
    }

    @Test
    @DisplayName("всплеск на один замер уровень не двигает")
    void aSingleSpikeDoesNothing() {
        var state = LoadLevels.State.initial(T0);
        state = step(state, FLOOD, 0);
        state = step(state, CALM, 1);
        assertThat(state.level()).isEqualTo(LoadLevel.NORMAL);
        assertThat(state.upSince()).as("счётчик подъёма сброшен").isNull();

        state = step(state, FLOOD, 2);
        assertThat(state.level()).as("отсчёт начинается заново, а не продолжается").isEqualTo(LoadLevel.NORMAL);
    }

    @Test
    @DisplayName("уровень поднимается сразу на перегрузку, если числа такие")
    void risesStraightToOverload() {
        var state = LoadLevels.State.initial(T0);
        state = step(state, FLOOD, 0);
        state = step(state, FLOOD, 2);
        assertThat(state.level()).isEqualTo(LoadLevel.OVERLOAD);
    }

    @Test
    @DisplayName("время разгрузки поднимает уровень само по себе, без роста потока")
    void clearTimeAloneRaisesTheLevel() {
        var state = LoadLevels.State.initial(T0);
        state = LoadLevels.next(state, T.overloadClearMin() + 1, 0, T, T0);
        state = LoadLevels.next(state, T.overloadClearMin() + 1, 0, T, at(2));
        assertThat(state.level()).isEqualTo(LoadLevel.OVERLOAD);
    }

    // ------------------------------------------------------------------ спуск

    @Test
    @DisplayName("спуск требует и запаса ниже порога, и down_hold_min")
    void fallNeedsSlackAndTime() {
        var state = overload();

        // Ниже порога перегрузки, но не ниже 80% от него: спуск даже не начинается.
        state = step(state, T.overloadRatio() * 0.9, 10);
        assertThat(state.level()).isEqualTo(LoadLevel.OVERLOAD);
        assertThat(state.downSince()).as("запаса не хватает — таймер спуска не идёт").isNull();

        state = step(state, CALM, 20);
        assertThat(state.downSince()).isEqualTo(at(20));
        state = step(state, CALM, 29);
        assertThat(state.level()).isEqualTo(LoadLevel.OVERLOAD);

        state = step(state, CALM, 30);                    // down_hold_min = 10
        assertThat(state.level()).isEqualTo(LoadLevel.NORMAL);
        assertThat(state.since()).isEqualTo(at(30));
    }

    @Test
    @DisplayName("возврат нагрузки посреди спуска отменяет его")
    void loadReturningCancelsTheFall() {
        var state = overload();
        state = step(state, CALM, 10);
        assertThat(state.downSince()).isEqualTo(at(10));

        state = step(state, FLOOD, 15);
        assertThat(state.downSince()).as("спуск отменён").isNull();
        assertThat(state.level()).isEqualTo(LoadLevel.OVERLOAD);

        state = step(state, CALM, 16);
        assertThat(state.downSince()).as("таймер начинается заново").isEqualTo(at(16));
        state = step(state, CALM, 25);
        assertThat(state.level()).isEqualTo(LoadLevel.OVERLOAD);
        state = step(state, CALM, 26);
        assertThat(state.level()).isEqualTo(LoadLevel.NORMAL);
    }

    @Test
    @DisplayName("колебание между high и normal во время спуска не сбрасывает таймер")
    void bouncingWhileClearingDoesNotResetTheTimer() {
        var state = overload();
        // Числа всё время глубоко ниже порога перегрузки, но сырой уровень скачет normal ↔ high.
        double belowOverload = T.overloadRatio() * T.downFactor() - 0.01;
        state = step(state, belowOverload, 5);
        assertThat(state.downSince()).isEqualTo(at(5));
        state = step(state, CALM, 10);
        assertThat(state.downSince()).as("условие спуска — про числа, а не про сырой уровень").isEqualTo(at(5));
        state = step(state, belowOverload, 14);
        assertThat(state.level()).isEqualTo(LoadLevel.OVERLOAD);

        state = step(state, belowOverload, 15);
        assertThat(state.level()).as("спуск доведён до конца, уровень берётся по числам")
                .isEqualTo(LoadLevel.HIGH);
    }

    @Test
    @DisplayName("уровень normal никуда не спускается и таймеров не копит")
    void normalStaysNormal() {
        var state = LoadLevels.State.initial(T0);
        state = step(state, CALM, 5);
        assertThat(state.level()).isEqualTo(LoadLevel.NORMAL);
        assertThat(state.since()).isEqualTo(T0);
        assertThat(state.upSince()).isNull();
        assertThat(state.downSince()).isNull();
    }

    // ------------------------------------------------------------------ устойчивость

    @Test
    @DisplayName("пустое состояние после рестарта читается как normal, а не как сбой")
    void missingStateReadsAsNormal() {
        var state = LoadLevels.next(null, 0, CALM, T, T0);
        assertThat(state.level()).isEqualTo(LoadLevel.NORMAL);
        assertThat(state.since()).isEqualTo(T0);

        var broken = new LoadLevels.State("чепуха", null, null, null);
        assertThat(LoadLevels.next(broken, 0, CALM, T, T0).level()).isEqualTo(LoadLevel.NORMAL);
    }

    @Test
    @DisplayName("нулевые выдержки означают мгновенный переход, а не вечное ожидание")
    void zeroHoldsSwitchImmediately() {
        var instant = new PlanningConfig.Thresholds(0.85, 1.10, 15, 30, 0.8, 0, 0);
        var state = LoadLevels.next(LoadLevels.State.initial(T0), 0, FLOOD, instant, T0);
        assertThat(state.level()).isEqualTo(LoadLevel.OVERLOAD);
        assertThat(LoadLevels.next(state, 0, CALM, instant, T0).level()).isEqualTo(LoadLevel.NORMAL);
    }

    /** Отделение в перегрузке с момента T0: две выдержки подъёма при заведомо высоких числах. */
    private static LoadLevels.State overload() {
        var state = LoadLevels.next(LoadLevels.State.initial(T0), 0, FLOOD, T, T0);
        state = LoadLevels.next(state, 0, FLOOD, T, at(2));
        assertThat(state.level()).isEqualTo(LoadLevel.OVERLOAD);
        return state;
    }
}
