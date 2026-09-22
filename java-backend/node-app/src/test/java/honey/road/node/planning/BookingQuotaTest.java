package honey.road.node.planning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code разрешено(s, h)} arithmetic (load-planning.md 5.1): pure, no database, no ambient time. */
class BookingQuotaTest {

    @Test
    @DisplayName("полная квота — вся мощность часа доступна")
    void fullQuotaGivesTheWholeHour() {
        // 15-минутные слоты, ёмкость 1: 4 слота в часе.
        assertThat(BookingQuota.allowed(15, 1, 1.0)).isEqualTo(4);
    }

    @Test
    @DisplayName("нулевая квота закрывает час целиком")
    void zeroQuotaClosesTheHour() {
        assertThat(BookingQuota.allowed(15, 1, 0.0)).isZero();
    }

    @Test
    @DisplayName("половинная квота округляется вниз")
    void halfQuotaRoundsDown() {
        assertThat(BookingQuota.allowed(15, 1, 0.5)).isEqualTo(2);
        // 3 слота × 0,5 = 1,5 → 1, а не 2: округление всегда вниз, иначе квота отдаёт больше объявленного.
        assertThat(BookingQuota.allowed(20, 1, 0.5)).isEqualTo(1);
    }

    @Test
    @DisplayName("ёмкость слота умножается, а не игнорируется")
    void capacityMultipliesIn() {
        assertThat(BookingQuota.allowed(30, 3, 1.0)).isEqualTo(6);
        assertThat(BookingQuota.allowed(30, 3, 0.5)).isEqualTo(3);
    }

    @Test
    @DisplayName("значение квоты вне 0..1 обрезается, а не ломает счёт")
    void quotaOutsideRangeIsClamped() {
        assertThat(BookingQuota.allowed(15, 1, 1.5)).isEqualTo(4);
        assertThat(BookingQuota.allowed(15, 1, -0.5)).isZero();
    }

    @Test
    @DisplayName("нулевая длительность или ёмкость — ноль разрешённых, а не деление на ноль")
    void degenerateServiceShapeGivesZero() {
        assertThat(BookingQuota.allowed(0, 1, 1.0)).isZero();
        assertThat(BookingQuota.allowed(15, 0, 1.0)).isZero();
    }
}
