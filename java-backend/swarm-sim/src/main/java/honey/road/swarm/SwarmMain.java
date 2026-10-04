package honey.road.swarm;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Swarm of virtual branches against a running center (final plan, C1): {@code N} branches in one process send
 * heartbeats and the full ticket life cycle through regional leaves, exactly in the formats of a branch node.
 *
 * <pre>
 *   docker compose --profile swarm up -d                     # four regional leaves next to the stand
 *   java -jar swarm-sim/target/swarm-sim-0.0.1-SNAPSHOT.jar --branches 40000 --duration 10m
 * </pre>
 */
public final class SwarmMain {
    static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    static final long PROGRESS_MS = 5_000;

    private SwarmMain() { }

    public static void main(String[] args) throws Exception {
        SwarmOptions o;
        try {
            o = SwarmOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        System.exit(run(o) ? 0 : 1);
    }

    static boolean run(SwarmOptions o) throws Exception {
        var stats = new SwarmStats();
        var wallStart = Instant.now();
        var virtualStart = o.virtualStart(wallStart);

        var urls = new ArrayList<String>();
        for (int r = 1; r <= o.regions(); r++) { urls.add(o.natsUrl(r)); }
        System.out.printf("Подключаюсь к региональным leaf: %s%n", String.join(", ", urls.stream().distinct().toList()));
        try (var sink = new NatsSink(urls, o.maxInFlight(), stats)) {
            var branches = new ArrayList<VirtualBranch>(o.branches());
            var byId = new HashMap<String, VirtualBranch>();
            for (int i = 0; i < o.branches(); i++) {
                var b = new VirtualBranch(i, o.regions(), o.clientsPerDay(), o.seed(), wallStart);
                b.natsUrl = o.natsUrl(b.region);
                b.eventsVia = sink.domain(b.natsUrl);
                branches.add(b);
                byId.put(b.id, b);
            }
            sink.listenConfig(byId);
            var domains = new LinkedHashMap<String, Long>();
            branches.forEach(b -> domains.merge(b.eventsVia, 1L, Long::sum));
            System.out.printf("Отделений %d → %s; старт виртуального дня %s (МСК), ускорение ×%s, пик ×%s, %s%n",
                    o.branches(), domains, ZonedDateTime.ofInstant(virtualStart, MOSCOW).toLocalTime().withNano(0),
                    o.speed(), o.peak(), o.duration().isZero() ? "до Ctrl+C" : "длительность " + o.duration());

            var timeline = new Timeline(branches, sink, o.peak(), o.notifications(), System.nanoTime() ^ o.seed());
            var schedule = new HeartbeatSchedule(o.branches(), o.heartbeat().toMillis());
            timeline.start(virtualStart);

            var stop = new CountDownLatch(1);
            var done = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                stop.countDown();
                try { done.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }));

            long t0 = System.nanoTime();
            long nextProgress = PROGRESS_MS;
            long maxLoopMs = 0;
            long prevAcked = 0, prevHb = 0, prevAt = 0;
            Instant virtualNow = virtualStart;
            try {
                while (stop.getCount() > 0) {
                    long loopStart = System.nanoTime();
                    long elapsed = (loopStart - t0) / 1_000_000;
                    if (!o.duration().isZero() && elapsed >= o.duration().toMillis()) { break; }
                    virtualNow = virtualStart.plusMillis((long) (elapsed * o.speed()));
                    timeline.advanceTo(virtualNow);
                    var v = virtualNow;
                    var real = wallStart.plusMillis(elapsed);
                    schedule.due(elapsed, i -> {
                        var b = branches.get(i);
                        sink.heartbeat(b, Heartbeats.snapshot(b, v, real, o.speed()));
                    });
                    maxLoopMs = Math.max(maxLoopMs, (System.nanoTime() - loopStart) / 1_000_000);
                    if (elapsed >= nextProgress) {
                        long acked = SwarmStats.total(stats.acked), hb = stats.heartbeats.sum();
                        System.out.printf("[%s] вирт. %s МСК · hb %d/с · событий %d/с (отпр. %d, подтв. %d, ошибок %d) · "
                                        + "в полёте %d · действий в очереди %d%n",
                                hms(elapsed), ZonedDateTime.ofInstant(virtualNow, MOSCOW).toLocalTime().withNano(0),
                                (hb - prevHb) * 1000 / Math.max(1, elapsed - prevAt),
                                (acked - prevAcked) * 1000 / Math.max(1, elapsed - prevAt),
                                SwarmStats.total(stats.published), acked, SwarmStats.total(stats.failed),
                                sink.inFlight(), timeline.pending());
                        prevAcked = acked;
                        prevHb = hb;
                        prevAt = elapsed;
                        nextProgress = elapsed + PROGRESS_MS;
                    }
                    stop.await(20, java.util.concurrent.TimeUnit.MILLISECONDS);
                }
                boolean drained = sink.drain(Duration.ofSeconds(30));
                var report = report(o, wallStart, virtualStart, virtualNow, domains, stats, maxLoopMs, drained);
                Files.writeString(o.report(), JsonMapper.builder()
                        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                        .enable(SerializationFeature.INDENT_OUTPUT).build().writeValueAsString(report));
                long failed = SwarmStats.total(stats.failed);
                System.out.printf("Готово: событий подтверждено %d, ошибок %d, heartbeat %d, конфигураций получено %d. "
                                + "Отчёт: %s%n", SwarmStats.total(stats.acked), failed, stats.heartbeats.sum(),
                        stats.configs.sum(), o.report().toAbsolutePath());
                if (!stats.errors.isEmpty()) { System.out.println("Ошибки: " + SwarmStats.sorted(stats.errors)); }
                return failed == 0 && drained;
            } finally {
                done.countDown();
            }
        }
    }

    static Map<String, Object> report(SwarmOptions o, Instant wallStart, Instant virtualStart, Instant virtualEnd,
                                      Map<String, Long> domains, SwarmStats stats, long maxLoopMs, boolean drained) {
        var r = new LinkedHashMap<String, Object>();
        r.put("started_at", wallStart);
        r.put("finished_at", Instant.now());
        r.put("real_seconds", Duration.between(wallStart, Instant.now()).toSeconds());
        r.put("virtual_from", virtualStart);
        r.put("virtual_to", virtualEnd);
        r.put("options", Map.of("branches", o.branches(), "regions", o.regions(), "clients_per_day", o.clientsPerDay(),
                "peak", o.peak(), "speed", o.speed(), "heartbeat_sec", o.heartbeat().toSeconds(), "seed", o.seed(),
                "notifications", o.notifications(), "nats", o.natsUrls()));
        r.put("branch_ids", "s<регион>-<номер>, например s1-00001");
        r.put("branches_by_region", domains);
        r.put("events_published", SwarmStats.sorted(stats.published));
        r.put("events_acked", SwarmStats.sorted(stats.acked));
        r.put("events_failed", SwarmStats.sorted(stats.failed));
        r.put("events_acked_total", SwarmStats.total(stats.acked));
        r.put("events_failed_total", SwarmStats.total(stats.failed));
        r.put("duplicates", stats.duplicates.sum());
        r.put("heartbeats", stats.heartbeats.sum());
        r.put("heartbeat_errors", stats.heartbeatErrors.sum());
        r.put("configs_received", stats.configs.sum());
        r.put("errors", SwarmStats.sorted(stats.errors));
        r.put("max_loop_ms", maxLoopMs);
        r.put("drained", drained);
        return r;
    }

    private static String hms(long ms) {
        long s = ms / 1000;
        return String.format("%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }

    static List<VirtualBranch> branches(int n, int regions, int clients, long seed, Instant startedAt) {
        var list = new ArrayList<VirtualBranch>(n);
        for (int i = 0; i < n; i++) { list.add(new VirtualBranch(i, regions, clients, seed, startedAt)); }
        return list;
    }
}
