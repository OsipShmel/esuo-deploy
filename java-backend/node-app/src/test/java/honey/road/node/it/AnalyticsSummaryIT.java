package honey.road.node.it;

import honey.road.contracts.event.Actor;
import honey.road.node.queue.application.AnalyticsQueries;
import honey.road.node.queue.application.IssueTicketCommand;
import honey.road.node.queue.application.Incidents;
import honey.road.node.queue.application.Walkin;
import honey.road.node.queue.domain.DomainException;
import honey.road.node.queue.domain.TicketSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Проверка ТЗ №10: аналитика руководителя (summary, windows, deviations, unfinished). */
class AnalyticsSummaryIT extends AbstractNodeIT {
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private final Actor operator = Actor.operator(E1.toString());

    @Autowired AnalyticsQueries analytics;
    @Autowired Walkin walkin;
    @Autowired Incidents incidents;

    private AnalyticsQueries.Period today() {
        return analytics.period(null, null);
    }

    private static Instant tomorrowAt(int hour) {
        return LocalDate.now(ZONE).plusDays(1).atTime(hour, 0).atZone(ZONE).toInstant();
    }

    private static long deviations(AnalyticsQueries.Deviations d, String type) {
        return d.counts().get(type);
    }

    private List<Map<String, Object>> items(AnalyticsQueries.Deviations d, String type) {
        return d.items().stream().filter(i -> type.equals(i.get("type"))).toList();
    }

    // ------------------------------------------------------------------ summary

    /** A, B — QR на посылки (обслужены), C — отменён, D — живая очередь на отправку (ждёт), E — запись на переводы. */
    private void scenario() {
        openWindow(W1, E1, PARCEL, SEND);
        var a = qr(PARCEL, "a");
        var b = qr(PARCEL, "b");
        var c = qrView(PARCEL, "c");
        walkin.execute(W1, SEND, "default", false, E1, "walkin-d");
        issue.execute(new IssueTicketCommand(TicketSource.appointment, FINANCE, tomorrowAt(10), "default", null, null), "appt-e");

        callNext.execute(W1, null);
        tickets.start(a, null, operator);
        tickets.complete(a, null, operator);
        callNext.execute(W1, null);
        tickets.complete(b, null, operator);
        cancel.execute(c.token());
    }

    @Test
    void summaryCountsTicketsByStatusSourceAndService() {
        scenario();
        var s = analytics.summary(today());

        assertThat(s.totals().issued()).isEqualTo(5);
        assertThat(s.totals().served()).isEqualTo(2);
        assertThat(s.totals().cancelled()).isEqualTo(1);
        assertThat(s.totals().noShow()).isZero();
        assertThat(s.totals().active()).isEqualTo(2);                        // D ждёт, E записан
        assertThat(s.totals().avgWaitSec()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(s.totals().maxWaitSec()).isGreaterThanOrEqualTo(s.totals().avgWaitSec());

        assertThat(s.bySource()).extracting(AnalyticsQueries.SourceStat::source, AnalyticsQueries.SourceStat::issued,
                        AnalyticsQueries.SourceStat::served)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("qr", 3, 2),
                        org.assertj.core.groups.Tuple.tuple("walkin", 1, 0),
                        org.assertj.core.groups.Tuple.tuple("appointment", 1, 0));
        assertThat(s.byService()).extracting(AnalyticsQueries.ServiceStat::serviceId, AnalyticsQueries.ServiceStat::issued,
                        AnalyticsQueries.ServiceStat::served)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(PARCEL, 3, 2),
                        org.assertj.core.groups.Tuple.tuple(SEND, 1, 0),
                        org.assertj.core.groups.Tuple.tuple(FINANCE, 1, 0));
    }

    @Test
    void waitIsMeasuredToTheFirstCallNotToTheLastRecall() {
        openWindow(W1, E1, PARCEL);
        var view = qrView(PARCEL, "slow");
        // Клиент ждал 10 минут: сдвигаем выдачу в прошлое, затем вызываем.
        jdbc.update("UPDATE tickets SET issued_at = now() - interval '10 minutes', eligible_from = now() - interval '10 minutes' WHERE id=?",
                view.ticketId());
        callNext.execute(W1, null);
        // Повторный вызов обновляет called_at, но ожидание считается по первому ticket.called.
        tickets.recall(view.ticketId(), null, operator);
        tickets.complete(view.ticketId(), null, operator);

        var s = analytics.summary(analytics.period(LocalDate.now(ZONE).minusDays(1).toString(), LocalDate.now(ZONE).toString()));
        assertThat(s.totals().served()).isEqualTo(1);
        assertThat(s.totals().maxWaitSec()).isBetween(595L, 605L);
    }

    @Test
    void periodAcceptsDatesAndInstantsAndRejectsGarbage() {
        var p = analytics.period("2026-09-01", "2026-09-01");
        assertThat(p.from()).isEqualTo(LocalDate.parse("2026-09-01").atStartOfDay(ZONE).toInstant());
        assertThat(p.to()).isEqualTo(LocalDate.parse("2026-09-02").atStartOfDay(ZONE).toInstant());   // дата в to — весь день
        assertThat(analytics.period("2026-09-01T00:00:00Z", "2026-09-01T12:00:00Z").to()).isEqualTo(Instant.parse("2026-09-01T12:00:00Z"));

        assertThatThrownBy(() -> analytics.period("yesterday", null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> analytics.period("2026-09-05", "2026-09-01"))
                .isInstanceOf(DomainException.class).hasMessageContaining("раньше");
    }

    @Test
    void emptyPeriodGivesZerosAndNoNulls() {
        var s = analytics.summary(analytics.period("2001-01-01", "2001-01-02"));
        assertThat(s.totals().issued()).isZero();
        assertThat(s.totals().avgWaitSec()).isNull();
        assertThat(s.bySource()).isEmpty();
        assertThat(s.byService()).isEmpty();
    }

    // ------------------------------------------------------------------ windows

    @Test
    void windowOpenTimeComesFromTheWindowJournalAndIsClippedToThePeriod() {
        // W2 работало с -3 ч до -1 ч; W3 открыто с -30 мин и всё ещё открыто.
        jdbc.update("INSERT INTO window_events(id, window_id, event_type, actor_id, payload, occurred_at) VALUES (?,?,?,?,CAST(? AS jsonb), now() - interval '3 hours')",
                java.util.UUID.randomUUID(), W2, "window.opened", null, "{\"reopened\": false}");
        jdbc.update("INSERT INTO window_events(id, window_id, event_type, actor_id, payload, occurred_at) VALUES (?,?,?,?,CAST(? AS jsonb), now() - interval '1 hour')",
                java.util.UUID.randomUUID(), W2, "window.closed", null, "{}");
        jdbc.update("INSERT INTO window_events(id, window_id, event_type, actor_id, payload, occurred_at) VALUES (?,?,?,?,CAST(? AS jsonb), now() - interval '30 minutes')",
                java.util.UUID.randomUUID(), W3, "window.opened", null, "{\"reopened\": false}");
        jdbc.update("UPDATE windows SET status='open', opened_at = now() - interval '30 minutes' WHERE id=?", W3);

        var now = Instant.now();
        var stats = analytics.windows(new AnalyticsQueries.Period(now.minusSeconds(6 * 3600), now)).stream()
                .collect(java.util.stream.Collectors.toMap(AnalyticsQueries.WindowStat::windowId, w -> w));

        assertThat(stats.get(W2).openSec()).isBetween(7195L, 7210L);          // 2 часа
        assertThat(stats.get(W3).openSec()).isBetween(1795L, 1815L);          // 30 минут, окно ещё открыто
        assertThat(stats.get(W2).idleSec()).isEqualTo(stats.get(W2).openSec());   // никого не обслужило
        assertThat(stats.get(W2).utilization()).isZero();

        // Период короче работы окна: считается только пересечение.
        var narrow = analytics.windows(new AnalyticsQueries.Period(now.minusSeconds(2 * 3600), now.minusSeconds(3600 + 1800))).stream()
                .filter(w -> w.windowId().equals(W2)).findFirst().orElseThrow();
        assertThat(narrow.openSec()).isBetween(1795L, 1805L);                  // с -2 ч до -1,5 ч
    }

    @Test
    void windowCountsServedClientsAndUtilization() {
        scenario();
        var w1 = analytics.windows(today()).stream().filter(w -> w.windowId().equals(W1)).findFirst().orElseThrow();
        assertThat(w1.served()).isEqualTo(2);
        assertThat(w1.utilization()).isBetween(0.0, 1.0);
        assertThat(w1.idleSec()).isGreaterThanOrEqualTo(0);
    }

    // ------------------------------------------------------------------ deviations

    @Test
    void overdueWaitingTicketIsReported() {
        var view = qrView(PARCEL, "late");
        jdbc.update("UPDATE tickets SET due_at = now() - interval '5 minutes' WHERE id=?", view.ticketId());

        var d = analytics.deviations(today());
        assertThat(deviations(d, "overdue_ticket")).isEqualTo(1);
        assertThat(((Number) items(d, "overdue_ticket").getFirst().get("overdue_sec")).longValue()).isBetween(295L, 310L);
    }

    @Test
    void noShowAndLongServiceAreReported() {
        openWindow(W1, E1, PARCEL);
        var absent = qr(PARCEL, "absent");
        var slow = qr(PARCEL, "slow");
        callNext.execute(W1, null);
        tickets.noShow(absent, null, operator);
        callNext.execute(W1, null);
        tickets.start(slow, null, operator);
        jdbc.update("UPDATE tickets SET serving_started_at = now() - interval '40 minutes' WHERE id=?", slow);   // порог 20 мин
        tickets.complete(slow, null, operator);

        var d = analytics.deviations(today());
        assertThat(deviations(d, "no_show")).isEqualTo(1);
        assertThat(deviations(d, "long_service")).isEqualTo(1);
        assertThat(((Number) items(d, "long_service").getFirst().get("duration_sec")).longValue()).isBetween(2395L, 2410L);
    }

    @Test
    void openWindowWithoutActivityIsIdleButBusyOrFreshOneIsNot() {
        // W3 «открыто» час назад без единого события и клиентов (S14); W1 только что открыто.
        jdbc.update("UPDATE windows SET status='open', opened_at = now() - interval '1 hour', current_employee_id=? WHERE id=?", E2, W3);
        openWindow(W1, E1, PARCEL);

        var idle = items(analytics.deviations(today()), "idle_window");
        assertThat(idle).hasSize(1);
        assertThat(idle.getFirst().get("window_id")).isEqualTo(W3);
        assertThat(((Number) idle.getFirst().get("idle_sec")).longValue()).isBetween(3590L, 3620L);
    }

    @Test
    void serviceWithWaitingClientsButNoOpenWindowIsReported() {
        openWindow(W1, E1, PARCEL);                                         // окна для «Отправки» нет
        qr(SEND, "s1");
        qr(SEND, "s2");
        qr(PARCEL, "p1");                                                   // а на посылки окно есть

        var d = analytics.deviations(today());
        var missing = items(d, "service_without_window");
        assertThat(missing).hasSize(1);
        assertThat(missing.getFirst().get("service_id")).isEqualTo(SEND);
        assertThat(((Number) missing.getFirst().get("waiting")).intValue()).isEqualTo(2);

        openWindow(W3, E2, SEND);                                           // руководитель открыл окно — отклонение ушло
        assertThat(deviations(analytics.deviations(today()), "service_without_window")).isZero();
    }

    @Test
    void openIncidentsAreListedUntilResolved() {
        var incident = incidents.create("technical", "Не работает принтер", W1, null, E1, operator);
        assertThat(deviations(analytics.deviations(today()), "open_incident")).isEqualTo(1);

        incidents.resolve(incident.id());
        assertThat(deviations(analytics.deviations(today()), "open_incident")).isZero();
    }

    @Test
    void cleanBranchHasNoDeviations() {
        var d = analytics.deviations(today());
        assertThat(d.items()).isEmpty();
        assertThat(d.counts().values()).allMatch(n -> n == 0);
    }

    // ------------------------------------------------------------------ unfinished

    @Test
    void unfinishedListsOnlyNonTerminalTicketsOfThePeriod() {
        scenario();
        var list = analytics.unfinished(today());
        assertThat(list).extracting(AnalyticsQueries.Unfinished::status).containsExactlyInAnyOrder("waiting", "booked");
        assertThat(list).extracting(AnalyticsQueries.Unfinished::serviceName).doesNotContainNull();
        assertThat(analytics.unfinished(analytics.period("2001-01-01", "2001-01-02"))).isEmpty();
    }
}
