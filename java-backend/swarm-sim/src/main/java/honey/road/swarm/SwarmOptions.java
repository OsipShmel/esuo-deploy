package honey.road.swarm;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Swarm run parameters ({@code --key value}). Defaults give a small local run against the four regional leaves of the
 * {@code swarm} compose profile.
 *
 * @param branches      virtual branches, ids {@code s{region}-{number}}
 * @param regions       regional leaves; branch {@code i} goes to region {@code i % regions + 1}
 * @param natsUrls      client URL of each regional leaf (region k uses entry {@code (k-1) % size})
 * @param clientsPerDay mean clients of a branch on a weekday; branches spread around it (×0.4…×2.5)
 * @param peak          flow multiplier on top of the day curve (1 = ordinary day, 3 = pension-day peak)
 * @param speed         virtual time per real second; events scale with it, heartbeats stay real-time
 * @param duration      run length in real time; zero = until Ctrl+C
 * @param startAt       Moscow wall time to start the virtual day at; {@code null} = now
 * @param startDate     date of the virtual day; {@code null} = today (Sunday is closed: no events)
 * @param heartbeat     heartbeat period of every branch (real time, like {@code esuo.nats.heartbeat-interval})
 * @param seed          seed of the flow model: same seed, same tickets (event ids are unique per run anyway)
 * @param notifications also emit {@code notification.requested}; off by default — the center would mail them out
 * @param maxInFlight   unacknowledged JetStream publishes allowed at once
 * @param report        JSON report path
 */
public record SwarmOptions(
        int branches,
        int regions,
        List<String> natsUrls,
        int clientsPerDay,
        double peak,
        double speed,
        Duration duration,
        LocalTime startAt,
        LocalDate startDate,
        Duration heartbeat,
        long seed,
        boolean notifications,
        int maxInFlight,
        Path report
) {
    public static final String USAGE = """
            swarm-sim: рой виртуальных отделений для замера центра (план финала, C1)

              --branches N        виртуальных отделений (1000)
              --regions K         региональных leaf (4)
              --nats URL[,URL]    адреса leaf регионов (nats://localhost:4231,…:4234)
              --clients N         клиентов в день у среднего отделения (150)
              --peak X            множитель пика (1)
              --speed X           ускорение времени: события ×X, heartbeat — реальные (1)
              --duration D        длительность: 30s, 10m, 1h; 0 — до Ctrl+C (10m)
              --start ЧЧ:ММ       виртуальное московское время старта (сейчас)
              --date ГГГГ-ММ-ДД   дата виртуального дня (сегодня; в воскресенье отделения закрыты)
              --heartbeat D       период heartbeat (10s)
              --seed N            зерно модели потока (42)
              --notifications     слать и notification.requested (центр их разошлёт)
              --max-in-flight N   неподтверждённых публикаций (10000)
              --report PATH       JSON-отчёт или папка для него (swarm-report-<время>.json)
            """;

    public SwarmOptions {
        if (branches < 1 || regions < 1 || natsUrls.isEmpty() || clientsPerDay < 1 || peak <= 0 || speed <= 0
                || heartbeat.isZero() || heartbeat.isNegative() || maxInFlight < 1) {
            throw new IllegalArgumentException("недопустимые параметры роя");
        }
        natsUrls = List.copyOf(natsUrls);
    }

    public static SwarmOptions parse(String... args) {
        int branches = 1000, regions = 4, clients = 150, maxInFlight = 10_000;
        double peak = 1, speed = 1;
        Duration duration = Duration.ofMinutes(10), heartbeat = Duration.ofSeconds(10);
        LocalTime start = null;
        LocalDate date = null;
        long seed = 42;
        boolean notifications = false;
        Path report = null;
        List<String> urls = List.of("nats://localhost:4231", "nats://localhost:4232", "nats://localhost:4233",
                "nats://localhost:4234");
        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            if (key.equals("--notifications")) { notifications = true; continue; }
            if (key.equals("-h") || key.equals("--help")) { throw new IllegalArgumentException(USAGE); }
            if (i + 1 >= args.length) { throw new IllegalArgumentException("нет значения для " + key + "\n" + USAGE); }
            String v = args[++i];
            switch (key) {
                case "--branches" -> branches = Integer.parseInt(v);
                case "--regions" -> regions = Integer.parseInt(v);
                case "--nats" -> urls = new ArrayList<>(Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
                case "--clients" -> clients = Integer.parseInt(v);
                case "--peak" -> peak = Double.parseDouble(v);
                case "--speed" -> speed = Double.parseDouble(v);
                case "--duration" -> duration = duration(v);
                case "--start" -> start = LocalTime.parse(v);
                case "--date" -> date = date(v);
                case "--heartbeat" -> heartbeat = duration(v);
                case "--seed" -> seed = Long.parseLong(v);
                case "--max-in-flight" -> maxInFlight = Integer.parseInt(v);
                case "--report" -> report = Path.of(v);
                default -> throw new IllegalArgumentException("неизвестный параметр " + key + "\n" + USAGE);
            }
        }
        var name = "swarm-report-" + java.time.LocalDateTime.now().withNano(0).toString().replace(':', '-') + ".json";
        if (report == null) {
            report = Path.of(name);
        } else if (java.nio.file.Files.isDirectory(report)) {
            report = report.resolve(name);                   // --report docs/demo/load: a new file per run
        }
        return new SwarmOptions(branches, regions, urls, clients, peak, speed, duration, start, date, heartbeat, seed,
                notifications, maxInFlight, report);
    }

    private static LocalDate date(String v) {
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("дата — ГГГГ-ММ-ДД: " + v);
        }
    }

    /** When the virtual day starts: {@code --date} and {@code --start} in Moscow, each defaulting to now. */
    public Instant virtualStart(Instant wallStart) {
        if (startAt == null && startDate == null) {
            return wallStart;
        }
        var day = startDate != null ? startDate : LocalDate.ofInstant(wallStart, SwarmMain.MOSCOW);
        var time = startAt != null ? startAt : LocalTime.ofInstant(wallStart, SwarmMain.MOSCOW);
        return ZonedDateTime.of(day, time, SwarmMain.MOSCOW).toInstant();
    }

    /** {@code 30s}, {@code 10m}, {@code 2h}, {@code 0}; a bare number is seconds. */
    static Duration duration(String v) {
        var s = v.trim().toLowerCase(Locale.ROOT);
        if (s.equals("0")) { return Duration.ZERO; }
        char unit = s.charAt(s.length() - 1);
        if (Character.isDigit(unit)) { return Duration.ofSeconds(Long.parseLong(s)); }
        long n = Long.parseLong(s.substring(0, s.length() - 1));
        return switch (unit) {
            case 's' -> Duration.ofSeconds(n);
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            default -> throw new IllegalArgumentException("длительность: 30s, 10m, 1h — получено " + v);
        };
    }

    public String natsUrl(int region) {
        return natsUrls.get((region - 1) % natsUrls.size());
    }
}
