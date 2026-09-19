package honey.road.node.it;

import honey.road.node.persistence.TicketRepository;
import honey.road.node.persistence.WindowRepository;
import honey.road.node.queue.scoring.HeuristicScoringStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code HeuristicScoringStrategy} (раздел 16.2): каскад уточнений, выбор в пределах slack, дедлайн как ограничитель. */
class HeuristicScoringIT extends AbstractNodeIT {
    @Autowired HeuristicScoringStrategy heuristic;
    @Autowired TicketRepository ticketRepo;
    @Autowired WindowRepository windowRepo;

    private void stats(UUID service, UUID employee, String category, double avgSec, int samples) {
        jdbc.update("""
                INSERT INTO handling_stats(service_id, employee_id, window_id, client_category, avg_duration_sec, sample_count, updated_at)
                VALUES (?, ?, NULL, ?, ?, ?, now())
                """, service, employee, category, avgSec, samples);
    }

    private void useHeuristic() {
        jdbc.update("UPDATE branch_config SET central_config = jsonb_set(central_config, '{scoring_strategy}', '\"heuristic\"') WHERE id=1");
    }

    private honey.road.node.queue.scoring.ScoreResult score(UUID ticketId, UUID employee) {
        var ticket = ticketRepo.byId("b01", ticketId, false);
        return heuristic.score(ticket, windowRepo.get("b01", W1), employee);
    }

    // ------------------------------------------------------------------ каскад

    @Test
    void noStatisticsFallsBackToDefaultDuration() {
        var r = score(qr(PARCEL, "a"), E1);
        assertThat(r.basis()).isEqualTo("fallback");
        assertThat(r.samples()).isZero();
        assertThat(r.predictedDuration().toSeconds()).isEqualTo(300);
        assertThat(r.value()).isEqualTo(1.0 / 300);
        assertThat(r.strategy()).isEqualTo("heuristic");
    }

    @Test
    void cascadeUsesTheMostSpecificLevelWithEnoughSamples() {
        stats(PARCEL, null, "default", 300, 20);          // услуга + категория, все сотрудники
        stats(PARCEL, E1, "default", 100, 6);              // сотрудник + услуга + категория
        var ordinary = qr(PARCEL, "a", "default");
        var pensioner = qr(PARCEL, "b", "pensioner");

        var level1 = score(ordinary, E1);
        assertThat(level1.basis()).isEqualTo("employee_service_category");
        assertThat(level1.predictedDuration().toSeconds()).isEqualTo(100);
        assertThat(level1.samples()).isEqualTo(6);

        var level2 = score(pensioner, E1);                 // нет своей категории у сотрудника → сотрудник + услуга
        assertThat(level2.basis()).isEqualTo("employee_service");
        assertThat(level2.predictedDuration().toSeconds()).isEqualTo(100);

        var level3 = score(ordinary, E2);                  // у E2 статистики нет → услуга + категория
        assertThat(level3.basis()).isEqualTo("service_category");
        assertThat(level3.predictedDuration().toSeconds()).isEqualTo(300);
        assertThat(level3.samples()).isEqualTo(20);

        var level4 = score(pensioner, E2);                 // у категории нет данных → услуга целиком
        assertThat(level4.basis()).isEqualTo("service");
        assertThat(level4.predictedDuration().toSeconds()).isEqualTo(300);
    }

    @Test
    void levelBelowMinSamplesIsSkipped() {
        stats(PARCEL, E1, "default", 50, 4);               // 4 < 5 наблюдений
        stats(PARCEL, null, "default", 400, 5);
        var r = score(qr(PARCEL, "a"), E1);
        assertThat(r.basis()).isEqualTo("service_category");
        assertThat(r.predictedDuration().toSeconds()).isEqualTo(400);
    }

    @Test
    void employeeLevelAveragesAcrossCategoriesWeightedBySamples() {
        stats(PARCEL, E1, "default", 100, 5);
        stats(PARCEL, E1, "svo", 400, 5);                  // средневзвешенное: (100·5 + 400·5) / 10 = 250
        var r = score(qr(PARCEL, "a", "pensioner"), E1);
        assertThat(r.basis()).isEqualTo("employee_service");
        assertThat(r.predictedDuration().toSeconds()).isEqualTo(250);
        assertThat(r.samples()).isEqualTo(10);
    }

    @Test
    void completionFeedsTheStatisticsAndTheForecastLearns() {
        openWindow(W1, E1, PARCEL);
        for (int i = 0; i < 5; i++) {
            var id = qr(PARCEL, "learn-" + i);
            callNext.execute(W1, null);
            // Статистика копит целые секунды: «обслуживали» 2 минуты.
            jdbc.update("UPDATE tickets SET called_at = now() - interval '2 minutes' WHERE id=?", id);
            tickets.complete(id, null, honey.road.contracts.event.Actor.operator(E1.toString()));
        }
        var r = score(qr(PARCEL, "after"), E1);
        assertThat(r.basis()).isEqualTo("employee_service_category");
        assertThat(r.samples()).isEqualTo(5);
        assertThat(r.predictedDuration().toSeconds()).isBetween(119L, 125L);
    }

    // ------------------------------------------------------------------ выбор

    private UUID[] twoHeadsInsideSlack() {
        openWindow(W1, E1, PARCEL, SEND);
        stats(PARCEL, null, "default", 600, 10);           // посылки — долго
        stats(SEND, null, "default", 120, 10);             // отправка — быстро
        var parcel = qr(PARCEL, "p");
        var send = qr(SEND, "s");
        // Обе головы допущены и в пределах slack (5 мин), посылка по дедлайну раньше.
        jdbc.update("UPDATE tickets SET due_at = now() + interval '2 minutes' WHERE id=?", parcel);
        jdbc.update("UPDATE tickets SET due_at = now() + interval '4 minutes' WHERE id=?", send);
        return new UUID[]{parcel, send};
    }

    private String eventField(UUID ticket, String field) {
        return jdbc.queryForObject("SELECT payload->>? FROM ticket_events WHERE ticket_id=? AND event_type='ticket.called'",
                String.class, field, ticket);
    }

    @Test
    void uniformPicksByDeadlineAndHeuristicPicksTheShorterServiceInsideSlack() {
        var t = twoHeadsInsideSlack();
        assertThat(callNext.execute(W1, null).ticketId()).isEqualTo(t[0]);         // uniform: EDF — посылка
        assertThat(eventField(t[0], "scoring_strategy")).isEqualTo("uniform");
    }

    @Test
    void heuristicChoosesShortServiceAndJournalsBasis() {
        useHeuristic();
        var t = twoHeadsInsideSlack();

        var called = callNext.execute(W1, null);

        assertThat(called.ticketId()).isEqualTo(t[1]);                              // короткая «Отправка»
        assertThat(eventField(t[1], "reason")).isEqualTo("score_in_slack");
        assertThat(eventField(t[1], "scoring_strategy")).isEqualTo("heuristic");
        assertThat(eventField(t[1], "model_version")).isEqualTo("handling_stats-v1");
        var payload = jdbc.queryForObject("SELECT payload::text FROM ticket_events WHERE ticket_id=? AND event_type='ticket.called'",
                String.class, t[1]);
        assertThat(payload).contains("\"basis\": \"service_category\"").contains("predicted_duration_sec").contains("\"samples\": 10");
    }

    @Test
    void overdueTicketBeatsAShorterOneEvenUnderHeuristic() {
        useHeuristic();
        var t = twoHeadsInsideSlack();
        jdbc.update("UPDATE tickets SET due_at = now() - interval '1 minute' WHERE id=?", t[0]);   // посылка просрочена

        assertThat(callNext.execute(W1, null).ticketId()).isEqualTo(t[0]);
        assertThat(eventField(t[0], "reason")).isEqualTo("overdue_edf");
    }

    @Test
    void headsOutsideSlackAreDecidedByDeadlineNotByScore() {
        useHeuristic();
        openWindow(W1, E1, PARCEL, SEND);
        stats(PARCEL, null, "default", 600, 10);
        stats(SEND, null, "default", 120, 10);
        var parcel = qr(PARCEL, "p");
        var send = qr(SEND, "s");
        jdbc.update("UPDATE tickets SET due_at = now() + interval '2 minutes' WHERE id=?", parcel);
        jdbc.update("UPDATE tickets SET due_at = now() + interval '30 minutes' WHERE id=?", send);   // далеко за slack

        assertThat(callNext.execute(W1, null).ticketId()).isEqualTo(parcel);
        assertThat(eventField(parcel, "reason")).isEqualTo("edf");
    }
}
