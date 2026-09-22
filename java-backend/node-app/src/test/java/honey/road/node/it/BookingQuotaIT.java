package honey.road.node.it;

import honey.road.node.planning.LoadLevel;
import honey.road.node.planning.LoadLevels;
import honey.road.node.planning.LoadPlanner;
import honey.road.node.planning.LoadStateRepository;
import honey.road.node.queue.application.IssueTicketCommand;
import honey.road.node.queue.domain.DomainException;
import honey.road.node.queue.domain.TicketSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Booking quota end to end (load-planning.md 5.1, 6.1). {@code PARCEL} in the demo catalog is 15-minute slots,
 * capacity 1 — four slots an hour — so {@code quota=0.5} lets exactly two of them through.
 *
 * <p>All fixtures cover the whole day at the same ratio ({@code opening_time 00:00}, a flat hourly curve, one
 * {@code planned_windows} step for the whole day) precisely so a test does not care what hour it happens to run
 * at — the same trick {@code LoadPlanningIT} uses, for the same reason.
 */
class BookingQuotaIT extends AbstractNodeIT {
    @Autowired LoadPlanner load;
    @Autowired LoadStateRepository state;

    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final String CURVE_24H;
    static {
        var b = new StringBuilder();
        for (int h = 0; h < 24; h++) { b.append(h == 0 ? "" : ",").append("\"").append(h).append("\":1"); }
        CURVE_24H = b.toString();
    }

    private static String planning(int plannedWindowsAllDay) {
        return """
                {"base_arrivals_per_day": 240, "utilization_target": 0.85,
                 "weekdays": {"mon":"normal","tue":"normal","wed":"normal","thu":"normal","fri":"normal","sat":"normal","sun":"normal"},
                 "profiles": {"normal": {"name":"День","flow_multiplier":1.0,
                              "opening_time":"00:00","closing_time":"23:59",
                              "hourly_curve":{%s},
                              "service_mix":{"parcel":1},"category_mix":{"default":1},
                              "planned_windows":{"0":%d}}},
                 "thresholds": {"up_hold_min": 0, "down_hold_min": 0},
                 "levers": {"booking_quota": {"normal":1.0,"high":0.5,"overload":0.0}}}
                """.formatted(CURVE_24H, plannedWindowsAllDay);
    }

    /** 0 windows all day: no capacity anywhere → {@code overload} every hour (load-planning.md 4.1, NO_CAPACITY). */
    private static final String OVERLOAD_ALL_DAY = planning(0);
    /** 1 window all day: ≈50 min of work against ≈51 min of capacity → {@code high} every hour. */
    private static final String HIGH_ALL_DAY = planning(1);
    /** 3 windows all day: comfortably {@code normal} every hour. */
    private static final String NORMAL_ALL_DAY = planning(3);

    private void publishPlanning(String section) {
        jdbc.update("UPDATE branch_config SET planning = CAST(? AS jsonb), effective_version = effective_version + 1 "
                + "WHERE id=1", section);
    }

    /** A bookable slot tomorrow at 10:00 branch time: inside {@code queue-defaults.yml}'s 09:00–18:00 window. */
    private static Instant slotTomorrow(int hour, int minute) {
        return LocalDate.now(ZONE).plusDays(1).atTime(LocalTime.of(hour, minute)).atZone(ZONE).toInstant();
    }

    // ------------------------------------------------------------------ закрытие записи

    @Test
    @DisplayName("перегруженный час закрыт для новой записи: 409 BOOKING_LIMITED, уже принятые не трогаются")
    void overloadedHourRejectsNewAppointments() {
        publishPlanning(OVERLOAD_ALL_DAY);
        var slot = slotTomorrow(10, 0);

        assertThatThrownBy(() -> issue.execute(
                new IssueTicketCommand(TicketSource.appointment, PARCEL, slot, "default", null, null), "bq-1"))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).code()).isEqualTo("BOOKING_LIMITED"));

        assertThat(count("SELECT count(*) FROM tickets WHERE source='appointment'")).isZero();
    }

    @Test
    @DisplayName("на перегруженный день слоты вообще не показываются, хотя окна физически свободны")
    void overloadedDayHidesSlotsEntirely() {
        publishPlanning(OVERLOAD_ALL_DAY);
        var date = LocalDate.now(ZONE).plusDays(1);

        assertThat(queries.slots(PARCEL, date)).isEmpty();
    }

    @Test
    @DisplayName("booking-limits называет каждый перегруженный час с уровнем и нулевой квотой")
    void bookingLimitsListsTheOverloadedHours() {
        publishPlanning(OVERLOAD_ALL_DAY);
        var date = LocalDate.now(ZONE).plusDays(1);

        var limits = queries.bookingLimits(date);

        assertThat(limits).hasSize(24);
        assertThat(limits).allSatisfy(h -> {
            assertThat(h.level()).isEqualTo(LoadLevel.OVERLOAD);
            assertThat(h.quota()).isZero();
        });
    }

    @Test
    @DisplayName("свободный день: booking-limits пуст, слоты обычные")
    void unrestrictedDayHasNoLimits() {
        publishPlanning(NORMAL_ALL_DAY);
        var date = LocalDate.now(ZONE).plusDays(1);

        assertThat(queries.bookingLimits(date)).isEmpty();
        assertThat(queries.slots(PARCEL, date)).isNotEmpty();

        var view = issue.execute(
                new IssueTicketCommand(TicketSource.appointment, PARCEL, slotTomorrow(10, 0), "default", null, null),
                "bq-normal");
        assertThat(view.status()).isEqualTo("booked");
    }

    @Test
    @DisplayName("частичная квота: разрешено ровно floor(слотов_в_часе × capacity × quota), дальше отказ")
    void partialQuotaAdmitsExactlyTheAllowedCount() {
        publishPlanning(HIGH_ALL_DAY);
        // 4 слота по 15 мин в часе, capacity 1, quota 0.5 → floor(4×1×0.5) = 2 разрешено.
        var first = issue.execute(
                new IssueTicketCommand(TicketSource.appointment, PARCEL, slotTomorrow(10, 0), "default", null, null),
                "bq-h1");
        var second = issue.execute(
                new IssueTicketCommand(TicketSource.appointment, PARCEL, slotTomorrow(10, 15), "default", null, null),
                "bq-h2");
        assertThat(first.status()).isEqualTo("booked");
        assertThat(second.status()).isEqualTo("booked");

        assertThatThrownBy(() -> issue.execute(
                new IssueTicketCommand(TicketSource.appointment, PARCEL, slotTomorrow(10, 30), "default", null, null),
                "bq-h3"))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).code()).isEqualTo("BOOKING_LIMITED"));

        // Уже принятые записи не отменяются никогда (load-planning.md 5.1).
        assertThat(queries.get(first.token()).status()).isEqualTo("booked");
        assertThat(queries.get(second.token()).status()).isEqualTo("booked");
        assertThat(count("SELECT count(*) FROM tickets WHERE source='appointment' AND status='booked'")).isEqualTo(2);
    }

    // ------------------------------------------------------------------ путь МП — тот же код

    @Test
    @DisplayName("запись из МП отклоняется тем же кодом BOOKING_LIMITED — единая точка входа")
    void mpPathGetsTheSameRejection() {
        publishPlanning(OVERLOAD_ALL_DAY);
        var slot = slotTomorrow(11, 0);

        assertThatThrownBy(() -> issue.fromMobileAppointment("mp-bq-1", PARCEL, slot, "default"))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).code()).isEqualTo("BOOKING_LIMITED"));
    }

    // ------------------------------------------------------------------ смешивание с текущим уровнем

    @Test
    @DisplayName("на ближайшие два часа берётся худшее из прогноза и текущего уровня; дальние часы — чистый прогноз")
    void currentLevelBlendsIntoTheNearHours() {
        publishPlanning(NORMAL_ALL_DAY);              // прогноз на весь день — normal, quota 1.0
        var now = Instant.now();
        state.save(new LoadLevels.State(LoadLevel.OVERLOAD, now, now, null), now);   // но сейчас реально overload
        var today = now.atZone(ZONE).toLocalDate();
        int hourNow = now.atZone(ZONE).getHour();

        assertThat(load.quotaFor(today, hourNow)).as("текущий час: живой уровень перекрывает прогноз").isZero();
        assertThat(load.quotaFor(today, (hourNow + 1) % 24)).as("следующий час — тоже").isZero();
        assertThat(load.quotaFor(today, (hourNow + 3) % 24)).as("дальний час — чистый прогноз, normal").isEqualTo(1.0);
    }

    // ------------------------------------------------------------------ честное ожидание в зале

    @Test
    @DisplayName("спокойное отделение не показывает нагрузку клиенту вовсе")
    void quietBranchShowsNoLoadField() {
        assertThat(queries.branch()).doesNotContainKey("load");
    }

    @Test
    @DisplayName("от warn_walkin_from и выше клиент видит уровень и честное ожидание")
    void loadAppearsFromTheConfiguredLevel() {
        openWindow(W1, E1, PARCEL);
        for (int i = 0; i < 3; i++) { qr(PARCEL, "hall-" + i); }
        var now = Instant.now();
        state.save(new LoadLevels.State(LoadLevel.OVERLOAD, now, now, null), now);

        @SuppressWarnings("unchecked")
        var load = (Map<String, Object>) queries.branch().get("load");
        assertThat(load).containsEntry("level", LoadLevel.OVERLOAD);
        assertThat((Double) load.get("expected_wait_min")).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("без секции planning запись не ограничивается вовсе")
    void withoutPlanningBookingIsUnrestricted() {
        // AbstractNodeIT уже сбрасывает planning в {} перед каждым тестом.
        assertThat(load.bookingQuota(LocalDate.now(ZONE).plusDays(1))).isEmpty();
        assertThat(load.quotaFor(LocalDate.now(ZONE).plusDays(1), 10)).isEqualTo(1.0);

        var view = issue.execute(
                new IssueTicketCommand(TicketSource.appointment, PARCEL, slotTomorrow(10, 0), "default", null, null),
                "bq-off");
        assertThat(view.status()).isEqualTo("booked");
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
