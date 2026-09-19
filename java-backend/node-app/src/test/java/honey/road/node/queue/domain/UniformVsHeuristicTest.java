package honey.road.node.queue.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Сравнение {@code uniform} и {@code heuristic} на детерминированной симуляции (раздел 16.3) и гарантия
 * ограничения ожидания (раздел 6.6). Сценарии подобраны так, что результат выводится вручную, а не подгоняется.
 * Сводная таблица пишется в {@code target/simulation/uniform-vs-heuristic.md} — её можно вставить в
 * {@code docs/demo/results.md} с меткой [Проверено] (синтетика).
 */
class UniformVsHeuristicTest {
    static final UUID FAST = UUID.fromString("00000000-0000-4000-8000-0000000000f1");
    static final UUID MEDIUM = UUID.fromString("00000000-0000-4000-8000-0000000000f2");
    static final UUID SLOW = UUID.fromString("00000000-0000-4000-8000-0000000000f3");
    static final Duration SLACK = Duration.ofMinutes(5);

    private static ToDoubleFunction<Ticket> uniform() {
        return t -> 1.0;
    }

    private static ToDoubleFunction<Ticket> heuristic(Map<UUID, Long> duration) {
        return t -> 1.0 / duration.get(t.serviceId());
    }

    // ------------------------------------------------------------------ вручную проверяемые сценарии

    /**
     * В момент 0 у окна две допущенные головы, обе в пределах slack: «посылки» 600 с (дедлайн 2 мин) и «письма» 60 с (4 мин).
     * uniform берёт по дедлайну: ожидание 0 и 600 → среднее 300 с. heuristic — короткую первой: 0 и 60 → среднее 30 с.
     */
    @Test
    void heuristicCutsMeanWaitWhenBothHeadsAreInsideSlack() {
        var duration = Map.of(SLOW, 600L, FAST, 60L);
        var jobs = List.of(new QueueSimulator.Job("A-slow", SLOW, 0, 120), new QueueSimulator.Job("B-fast", FAST, 0, 240));
        var windows = List.<Set<UUID>>of(Set.of(SLOW, FAST));

        var u = QueueSimulator.run(jobs, windows, duration, uniform(), SLACK);
        var h = QueueSimulator.run(jobs, windows, duration, heuristic(duration), SLACK);

        assertEquals("A-slow", u.picks().getFirst().job());
        assertEquals("B-fast", h.picks().getFirst().job());
        assertEquals(QueueSelection.REASON_SCORE, h.picks().getFirst().reason());
        assertEquals(300.0, u.meanWait(), 1e-9);
        assertEquals(30.0, h.meanWait(), 1e-9);
        assertEquals(u.makespanSec(), h.makespanSec());                      // работа та же: выигрыш только в ожидании
    }

    /**
     * Просроченный талон score не переигрывает: «посылки» уже просрочены (дедлайн 0), поэтому даже heuristic берёт их первыми.
     */
    @Test
    void overdueTicketIsNeverBypassedByAShorterOne() {
        var duration = Map.of(SLOW, 600L, FAST, 60L);
        var jobs = List.of(new QueueSimulator.Job("A-slow", SLOW, 0, 0), new QueueSimulator.Job("B-fast", FAST, 0, 240));

        var h = QueueSimulator.run(jobs, List.of(Set.of(SLOW, FAST)), duration, heuristic(duration), SLACK);

        assertEquals("A-slow", h.picks().getFirst().job());
        assertEquals(QueueSelection.REASON_OVERDUE, h.picks().getFirst().reason());
    }

    /**
     * Ограничение ожидания (6.6): долгая услуга не «голодает». Окно одно; быстрые клиенты (30 с) приходят каждые 30 с
     * и всегда есть в очереди, долгий (600 с, дедлайн 300 с) пришёл первым. Пока долгий не просрочен, heuristic
     * предпочитает быстрых; в момент 300 с он просрочен, его дедлайн раньше всех быстрых (≥ 330 с) — и его берут
     * сразу, как только окно свободно: ожидание ровно 300 с, не больше дедлайна плюс одно обслуживание быстрого.
     */
    @Test
    void longServiceIsNotStarvedByAStreamOfShortOnes() {
        var duration = Map.of(SLOW, 600L, FAST, 30L);
        var jobs = new ArrayList<QueueSimulator.Job>();
        jobs.add(new QueueSimulator.Job("slow", SLOW, 0, 300));
        for (int k = 0; k < 40; k++) {
            jobs.add(new QueueSimulator.Job("fast-" + k, FAST, k * 30L, 300));
        }
        var windows = List.<Set<UUID>>of(Set.of(SLOW, FAST));

        var h = QueueSimulator.run(jobs, windows, duration, heuristic(duration), SLACK);
        var u = QueueSimulator.run(jobs, windows, duration, uniform(), SLACK);

        assertEquals(300L, h.waitSec().get("slow"));                          // отложен на весь дедлайн, но не дольше
        assertTrue(h.waitSec().get("slow") <= 300 + duration.get(FAST));      // граница из 6.6
        assertEquals(0L, u.waitSec().get("slow"));                            // EDF при равных дедлайнах: первым пришёл — первым обслужен
        var slowPick = h.picks().stream().filter(p -> p.job().equals("slow")).findFirst().orElseThrow();
        assertEquals(QueueSelection.REASON_OVERDUE, slowPick.reason());
    }

    // ------------------------------------------------------------------ сводка на «типичном» потоке

    /**
     * Псевдослучайный поток (seed 42), два окна, три услуги. Строго проверяем только выводимое:
     * обе стратегии выполняют один и тот же объём работы и никого не теряют; числа для отчёта пишем в файл.
     */
    @Test
    void randomizedDayIsServedCompletelyByBothStrategiesAndReportIsWritten() throws IOException {
        var duration = Map.of(FAST, 120L, MEDIUM, 300L, SLOW, 600L);
        var rnd = new Random(42);
        var jobs = new ArrayList<QueueSimulator.Job>();
        long t = 0;
        var services = List.of(FAST, FAST, MEDIUM, MEDIUM, SLOW);
        for (int i = 0; i < 120; i++) {
            t += 20 + rnd.nextInt(160);                                       // в среднем ~ раз в 100 с
            jobs.add(new QueueSimulator.Job("c" + i, services.get(rnd.nextInt(services.size())), t, 1200));
        }
        var windows = List.<Set<UUID>>of(Set.of(FAST, MEDIUM, SLOW), Set.of(FAST, MEDIUM, SLOW));

        var u = QueueSimulator.run(jobs, windows, duration, uniform(), SLACK);
        var h = QueueSimulator.run(jobs, windows, duration, heuristic(duration), SLACK);

        assertEquals(jobs.size(), u.doneSec().size());
        assertEquals(jobs.size(), h.doneSec().size());
        long busy = jobs.stream().mapToLong(j -> duration.get(j.service())).sum();
        assertTrue(u.makespanSec() >= busy / 2 && h.makespanSec() >= busy / 2);   // нижняя граница по загрузке двух окон

        var report = new StringBuilder("# uniform vs heuristic (синтетика, seed 42)\n\n")
                .append("| Стратегия | Клиентов | Среднее ожидание, с | Максимум, с | Завершение работы, с |\n|---|---|---|---|---|\n");
        row(report, "uniform", u);
        row(report, "heuristic", h);
        var dir = Path.of("target", "simulation");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("uniform-vs-heuristic.md"), report.toString());
    }

    private static void row(StringBuilder sb, String name, QueueSimulator.Result r) {
        sb.append(String.format("| %s | %d | %.1f | %d | %d |%n", name, r.doneSec().size(), r.meanWait(), r.maxWait(), r.makespanSec()));
    }
}
