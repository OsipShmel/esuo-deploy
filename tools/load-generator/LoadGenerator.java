import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ESUO load generator (design doc 3.2, 16.1): single file, no dependencies: {@code java LoadGenerator.java <mode> [options]}.
 *
 * <pre>
 * live     Live stream against a running node via REST: clients arrive (QR and walk-in), operators call, serve and
 *          complete. Durations follow the 16.1 patterns (services differ, pensioner slower, one fast and one slow employee).
 *          --node http://localhost:8080  --operators 2  --minutes 3  --speed 30  --arrivals-per-min 12
 *          --password operator123  --qr demo:b01  --seed 42
 *          --speed accelerates service (30: "5 minutes" pass in 10 seconds); stats keep whole seconds.
 *
 * history  Synthetic multi-week history as SQL (served tickets, journal, windows, handling_stats):
 *            java LoadGenerator.java history --days 21 --per-day 60 --out history.sql
 *            docker exec -i esuo-postgres-b01 psql -U esuo -d esuo &lt; history.sql
 *          Idempotent: tickets ON CONFLICT DO NOTHING, handling_stats rebuilt from served tickets.
 *          Requires the node demo catalog (DEMO_DATA=true).
 * </pre>
 */
public class LoadGenerator {
    // Node demo catalog (DatabaseBootstrap): services, windows, employees.
    static final Map<String, String> SERVICE_IDS = Map.of(
            "parcel", "00000000-0000-4000-8000-000000000001",
            "send", "00000000-0000-4000-8000-000000000004",
            "finance", "00000000-0000-4000-8000-000000000005");
    static final Map<String, String> PREFIX = Map.of("parcel", "П", "send", "О", "finance", "Ф");
    static final Map<String, Integer> SHARE = Map.of("parcel", 45, "send", 35, "finance", 20);          // share of the stream, %
    static final Map<String, Double> BASE_SEC = Map.of("parcel", 240.0, "send", 420.0, "finance", 600.0);  // parcel pickup is faster than a transfer
    static final String WINDOW_1 = "00000000-0000-4000-8000-000000000003";   // parcels + send, employee 1 (fast)
    static final String WINDOW_2 = "00000000-0000-4000-8000-000000000007";   // parcels + finance, employee 2 (slow)
    static final String EMPLOYEE_1 = "00000000-0000-4000-8000-000000000002";
    static final String EMPLOYEE_2 = "00000000-0000-4000-8000-000000000006";
    static final Map<String, Integer> TARGET_WAIT_QR = Map.of("default", 20, "pensioner", 15);
    static final Map<String, Integer> TARGET_WAIT_WALKIN = Map.of("default", 30, "pensioner", 20);

    public static void main(String[] args) throws Exception {
        var mode = args.length > 0 && !args[0].startsWith("--") ? args[0] : "live";
        var opts = Opts.parse(args);
        switch (mode) {
            case "live" -> Live.run(opts);
            case "history" -> History.run(opts);
            default -> {
                System.err.println("Режимы: live | history. Подробности — в заголовке файла.");
                System.exit(2);
            }
        }
    }

    // ------------------------------------------------------------------ common

    record Opts(Map<String, String> values) {
        static Opts parse(String[] args) {
            var m = new HashMap<String, String>();
            for (int i = 0; i < args.length; i++) {
                if (args[i].startsWith("--")) {
                    var key = args[i].substring(2);
                    m.put(key, i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "true");
                }
            }
            return new Opts(m);
        }

        String str(String k, String d) { return values.getOrDefault(k, d); }
        int num(String k, int d) { return Integer.parseInt(values.getOrDefault(k, String.valueOf(d))); }
        double dbl(String k, double d) { return Double.parseDouble(values.getOrDefault(k, String.valueOf(d))); }
    }

    /** Duration multiplier by client category (16.1: pensioner takes longer). */
    static double categoryFactor(String service, String category) {
        return switch (category) {
            case "pensioner" -> service.equals("parcel") ? 1.2 : 1.5;
            default -> 1.0;
        };
    }

    /** Log-normal noise around the mean: positive, heavy-tailed durations. */
    static double noise(Random rnd) {
        return Math.exp(rnd.nextGaussian() * 0.25 - 0.03125);
    }

    static String pickService(Random rnd) {
        int x = rnd.nextInt(100), acc = 0;
        for (var e : new String[]{"parcel", "send", "finance"}) {
            acc += SHARE.get(e);
            if (x < acc) return e;
        }
        return "parcel";
    }

    static String pickCategory(Random rnd, boolean pensionDay) {
        int x = rnd.nextInt(100);
        int pensioner = pensionDay ? 45 : 15;
        if (x < pensioner) return "pensioner";
        return "default";
    }

    // ------------------------------------------------------------------ minimal JSON

    static final class Json {
        private final String s;
        private int i;

        private Json(String s) { this.s = s; }

        static Object parse(String s) {
            try {
                var p = new Json(s);
                var v = p.value();
                return v;
            } catch (RuntimeException e) {
                return null;
            }
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> obj(String s) {
            return parse(s) instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        }

        @SuppressWarnings("unchecked")
        static List<Object> list(String s) {
            return parse(s) instanceof List<?> l ? (List<Object>) l : List.of();
        }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        private Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') {
                var m = new LinkedHashMap<String, Object>();
                i++; ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws();
                    var k = string();
                    ws(); i++;                                   // ':'
                    m.put(k, value());
                    ws();
                    if (s.charAt(i++) == '}') return m;
                }
            }
            if (c == '[') {
                var l = new ArrayList<Object>();
                i++; ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    l.add(value());
                    ws();
                    if (s.charAt(i++) == ']') return l;
                }
            }
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return true; }
            if (s.startsWith("false", i)) { i += 5; return false; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            return Double.parseDouble(s.substring(st, i));
        }

        private String string() {
            var sb = new StringBuilder();
            i++;                                                 // opening quote
            while (s.charAt(i) != '"') {
                char c = s.charAt(i++);
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        default -> sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
            i++;
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------ live

    static final class Live {
        record Resp(int status, String body) {
            Map<String, Object> json() { return Json.obj(body); }
            String code() { return String.valueOf(json().get("code")); }
        }

        static HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        static String node;

        static Resp call(String method, String path, String token, String body, String idem) {
            try {
                var b = HttpRequest.newBuilder(URI.create(node + "/api/v1" + path)).timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json");
                if (token != null) b.header("Authorization", "Bearer " + token);
                if (idem != null) b.header("Idempotency-Key", idem);
                b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
                var r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                return new Resp(r.statusCode(), r.body());
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return new Resp(0, e.toString());
            }
        }

        static final AtomicInteger issued = new AtomicInteger(), served = new AtomicInteger(), noShows = new AtomicInteger(),
                errors = new AtomicInteger();
        static volatile boolean stop;

        static void run(Opts o) throws Exception {
            node = o.str("node", "http://localhost:8080");
            int operators = Math.min(2, Math.max(1, o.num("operators", 2)));
            int minutes = o.num("minutes", 3);
            double speed = o.dbl("speed", 30);
            double perMin = o.dbl("arrivals-per-min", 12);
            var password = o.str("password", System.getenv().getOrDefault("DEMO_OPERATOR_PASSWORD", "operator123"));
            var rnd = new Random(o.num("seed", 42));

            var branch = call("GET", "/public/branch", null, null, null);
            if (branch.status() != 200) fail("узел недоступен: " + branch.status() + " " + branch.body());
            var qr = o.str("qr", "demo:" + branch.json().getOrDefault("id", "b01"));
            var codeById = new HashMap<String, String>();
            var idByCode = new HashMap<String, String>();
            @SuppressWarnings("unchecked")
            var services = (List<Map<String, Object>>) (List<?>) (branch.json().get("services") instanceof List<?> l ? l : List.of());
            for (var s : services) {
                codeById.put((String) s.get("id"), (String) s.get("code"));
                idByCode.put((String) s.get("code"), (String) s.get("id"));
            }
            if (idByCode.isEmpty()) fail("у отделения нет услуг");

            var logins = List.of("operator", "operator2").subList(0, operators);
            var tokens = new ArrayList<String>();
            for (var login : logins) {
                var r = call("POST", "/auth/login", null, "{\"login\":\"" + login + "\",\"password\":\"" + password + "\"}", null);
                if (r.status() != 200) fail("логин " + login + ": " + r.status() + " " + r.body());
                tokens.add((String) r.json().get("access_token"));
            }
            var windowsResp = call("GET", "/windows", tokens.get(0), null, null);
            var windows = Json.list(windowsResp.body());
            if (windows.size() < operators) fail("окон меньше, чем операторов");

            System.out.printf("Нагрузка: %d оператор(а), %d мин, ускорение x%.0f, поток %.0f клиентов/мин%n", operators, minutes, speed, perMin);
            var threads = new ArrayList<Thread>();
            var windowIds = new ArrayList<String>();
            for (int w = 0; w < operators; w++) {
                @SuppressWarnings("unchecked")
                var win = (Map<String, Object>) windows.get(w);
                var id = (String) win.get("id");
                windowIds.add(id);
                var open = call("POST", "/windows/" + id + "/open", tokens.get(w),
                        "{\"service_ids\":" + JsonOut.list(win.get("service_ids")) + "}", null);
                if (open.status() != 200) fail("не открылось окно " + id + ": " + open.body());
                double employeeFactor = w == 0 ? 0.75 : 1.35;                         // fast and slow employee
                var token = tokens.get(w);
                var wrnd = new Random(rnd.nextLong());
                threads.add(Thread.ofVirtual().name("operator-" + w).start(() -> operator(token, id, codeById, employeeFactor, speed, wrnd)));
            }

            long end = System.currentTimeMillis() + minutes * 60_000L;
            long rushFrom = end - minutes * 30_000L;                                   // second half: ×2 surge
            int deviceSeq = 0;
            while (System.currentTimeMillis() < end) {
                double rate = perMin * (System.currentTimeMillis() > rushFrom ? 2.0 : 1.0);
                Thread.sleep((long) (-Math.log(1 - rnd.nextDouble()) * 60_000 / rate));
                var code = pickService(rnd);
                var serviceId = idByCode.get(code);
                if (serviceId == null) continue;
                var category = pickCategory(rnd, false);
                var key = "lg-" + UUID.randomUUID();
                Resp r;
                if (rnd.nextInt(100) < 60) {
                    r = call("POST", "/public/qr-tickets", null, "{\"qr_code\":\"" + qr + "\",\"service_id\":\"" + serviceId
                            + "\",\"client_category\":\"" + category + "\",\"device_id\":\"lg-dev-" + (deviceSeq++) + "-" + rnd.nextInt(1_000_000) + "\"}", key);
                } else {
                    int w = rnd.nextInt(operators);
                    r = call("POST", "/windows/" + windowIds.get(w) + "/walkin", tokens.get(w),
                            "{\"service_id\":\"" + serviceId + "\",\"client_category\":\"" + category + "\",\"call_now\":false}", key);
                }
                if (r.status() == 201) issued.incrementAndGet();
                else if (!(r.status() == 422 && r.code().equals("SERVICE_NOT_IN_WINDOW"))) errors.incrementAndGet();
            }
            System.out.println("Поток клиентов закончен, операторы дообслуживают очередь…");
            Thread.sleep(Duration.ofSeconds(15).toMillis());
            stop = true;
            for (var t : threads) t.join(20_000);
            for (int w = 0; w < operators; w++) call("POST", "/windows/" + windowIds.get(w) + "/close", tokens.get(w), "{\"mode\":\"graceful\"}", null);
            System.out.printf("Готово: выдано %d, обслужено %d, неявок %d, ошибок запросов %d%n", issued.get(), served.get(), noShows.get(), errors.get());
            System.out.println("Смотрите аналитику: GET /api/v1/admin/analytics/summary, /windows, /deviations");
        }

        static void operator(String token, String windowId, Map<String, String> codeById, double employeeFactor, double speed, Random rnd) {
            while (!stop) {
                var next = call("POST", "/windows/" + windowId + "/call-next", token, null, null);
                if (next.status() != 200) {
                    sleep(1000);
                    continue;
                }
                var t = next.json();
                var id = (String) t.get("ticket_id");
                int version = ((Number) t.get("version")).intValue();
                var service = codeById.getOrDefault(((Map<?, ?>) t.get("service")).get("id"), "parcel");
                var category = (String) t.get("client_category");
                if (rnd.nextInt(100) < 3) {                                            // client did not show up
                    call("POST", "/tickets/" + id + "/no-show", token, "{\"expected_version\":" + version + "}", null);
                    noShows.incrementAndGet();
                    continue;
                }
                var started = call("POST", "/tickets/" + id + "/start", token, "{\"expected_version\":" + version + "}", null);
                if (started.status() != 200) { errors.incrementAndGet(); continue; }
                version = ((Number) started.json().get("version")).intValue();
                double seconds = BASE_SEC.getOrDefault(service, 300.0) * categoryFactor(service, category) * employeeFactor * noise(rnd);
                sleep((long) (seconds * 1000 / speed));
                var done = call("POST", "/tickets/" + id + "/complete", token, "{\"expected_version\":" + version + "}", null);
                if (done.status() == 200) served.incrementAndGet(); else errors.incrementAndGet();
            }
        }

        static void sleep(long ms) {
            try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        static void fail(String message) {
            System.err.println("Ошибка: " + message);
            System.exit(1);
        }
    }

    /** Serializes simple values parsed by {@link Json} back. */
    static final class JsonOut {
        static String list(Object o) {
            if (!(o instanceof List<?> l)) return "[]";
            var sb = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) sb.append(i > 0 ? "," : "").append('"').append(l.get(i)).append('"');
            return sb.append(']').toString();
        }
    }

    // ------------------------------------------------------------------ history

    static final class History {
        record Job(String service, String category, String source, Instant arrival, Instant due, UUID id) {}

        static void run(Opts o) throws IOException {
            int days = o.num("days", 21);
            int perDay = o.num("per-day", 60);
            long seed = o.num("seed", 42);
            var out = Path.of(o.str("out", "history.sql"));
            var zone = ZoneId.of(o.str("zone", "Europe/Moscow"));
            var sql = new StringBuilder("-- Синтетическая история ЕСУО: ").append(days).append(" дн., ~").append(perDay).append(" клиентов/день, seed ")
                    .append(seed).append("\nBEGIN;\n");
            var today = LocalDate.now(zone);
            int total = 0;
            for (int d = days; d >= 1; d--) {
                var day = today.minusDays(d);
                total += day(sql, day, zone, perDay, new Random(seed * 1000 + d), seed);
            }
            // handling_stats is rebuilt from served tickets, so the script can be rerun.
            sql.append("""
                    DELETE FROM handling_stats;
                    INSERT INTO handling_stats(service_id, employee_id, window_id, client_category, avg_duration_sec, sample_count, updated_at)
                    SELECT service_id, employee_id, NULL, client_category,
                           avg(EXTRACT(EPOCH FROM (completed_at - COALESCE(serving_started_at, called_at))))::double precision, count(*), now()
                      FROM tickets WHERE status = 'done' AND completed_at > COALESCE(serving_started_at, called_at) AND employee_id IS NOT NULL
                     GROUP BY service_id, employee_id, client_category;
                    INSERT INTO handling_stats(service_id, employee_id, window_id, client_category, avg_duration_sec, sample_count, updated_at)
                    SELECT service_id, NULL, NULL, client_category,
                           avg(EXTRACT(EPOCH FROM (completed_at - COALESCE(serving_started_at, called_at))))::double precision, count(*), now()
                      FROM tickets WHERE status = 'done' AND completed_at > COALESCE(serving_started_at, called_at)
                     GROUP BY service_id, client_category;
                    COMMIT;
                    """);
            Files.writeString(out, sql.toString());
            System.out.printf("Записано %s: %d талонов за %d дн.%nПрименить: docker exec -i esuo-postgres-b01 psql -U esuo -d esuo < %s%n", out, total, days, out);
        }

        /** One day: client stream with a lunch peak and pension days, served by two windows via EDF. */
        static int day(StringBuilder sql, LocalDate day, ZoneId zone, int perDay, Random rnd, long seed) {
            boolean pensionDay = day.getDayOfMonth() == 3 || day.getDayOfMonth() == 13 || day.getDayOfMonth() == 23;
            int count = (int) Math.round(perDay * (pensionDay ? 1.6 : 1.0) * (0.8 + 0.4 * rnd.nextDouble()));
            var open = day.atTime(LocalTime.of(9, 0)).atZone(zone).toInstant();
            var close = day.atTime(LocalTime.of(18, 0)).atZone(zone).toInstant();

            var jobs = new ArrayList<Job>();
            for (int i = 0; i < count; i++) {
                // Hourly density: morning and lunch (12–14) are denser.
                double x = rnd.nextDouble();
                double hour = x < 0.35 ? 9 + rnd.nextDouble() * 3 : x < 0.65 ? 12 + rnd.nextDouble() * 2 : 14 + rnd.nextDouble() * 3.9;
                var arrival = open.plusSeconds((long) ((hour - 9) * 3600));
                var service = pickService(rnd);
                var category = pickCategory(rnd, pensionDay);
                var source = rnd.nextInt(100) < 62 ? "qr" : "walkin";
                var wait = (source.equals("qr") ? TARGET_WAIT_QR : TARGET_WAIT_WALKIN).get(category);
                jobs.add(new Job(service, category, source, arrival, arrival.plusSeconds(wait * 60L),
                        UUID.nameUUIDFromBytes(("t" + seed + day + i).getBytes(StandardCharsets.UTF_8))));
            }
            jobs.sort(Comparator.comparing(Job::arrival));

            // Two windows: window 1 (parcels, send; fast employee), window 2 (parcels, finance; slow).
            var windowFree = new Instant[]{open, open};
            var windowServices = new List[]{List.of("parcel", "send"), List.of("parcel", "finance")};
            var windowIds = new String[]{WINDOW_1, WINDOW_2};
            var employees = new String[]{EMPLOYEE_1, EMPLOYEE_2};
            var factors = new double[]{0.75, 1.35};
            var waiting = new ArrayList<>(jobs);
            var numbers = new HashMap<String, Integer>();
            var served = 0;

            for (int w = 0; w < 2; w++) {
                sql.append(windowEvent(windowIds[w], "window.opened", "{\"reopened\": false}", open, day, w));
            }
            int seqInDay = 0;
            while (!waiting.isEmpty()) {
                int w = windowFree[0].compareTo(windowFree[1]) <= 0 ? 0 : 1;
                var at = windowFree[w];
                var ready = new ArrayList<Job>();
                for (var j : waiting) {
                    if (!j.arrival().isAfter(at) && windowServices[w].contains(j.service())) ready.add(j);
                }
                if (ready.isEmpty()) {
                    var next = waiting.stream().filter(j -> windowServices[w].contains(j.service())).map(Job::arrival).min(Instant::compareTo);
                    if (next.isEmpty()) { windowFree[w] = Instant.MAX; if (windowFree[1 - w].equals(Instant.MAX)) break; continue; }
                    windowFree[w] = next.get();
                    continue;
                }
                var job = ready.stream().min(Comparator.comparing(Job::due).thenComparing(Job::arrival)).orElseThrow();
                waiting.remove(job);
                long serviceSec = Math.max(20, Math.round(BASE_SEC.get(job.service()) * categoryFactor(job.service(), job.category())
                        * factors[w] * noise(rnd)));
                var called = at;
                var started = called.plusSeconds(3 + rnd.nextInt(20));                 // client comes to the window
                var completed = started.plusSeconds(serviceSec);
                windowFree[w] = completed;
                if (completed.isAfter(close.plus(Duration.ofHours(2)))) continue;      // too late, branch closed
                int n = numbers.merge(job.service(), 1, Integer::sum);
                ticket(sql, job, day, n, called, started, completed, windowIds[w], employees[w], ++seqInDay);
                served++;
            }
            for (int w = 0; w < 2; w++) {
                sql.append(windowEvent(windowIds[w], "window.closed", "{\"reason\": \"end_of_day\"}", close, day, w));
            }
            return served;
        }

        static void ticket(StringBuilder sql, Job j, LocalDate day, int number, Instant called, Instant started, Instant completed,
                           String window, String employee, int order) {
            var id = j.id();
            var token = UUID.nameUUIDFromBytes(("token" + id).getBytes(StandardCharsets.UTF_8));
            var display = PREFIX.get(j.service()) + "-" + String.format("%03d", number);
            sql.append("INSERT INTO tickets(id, branch_id, public_token, display_number, service_id, source, client_category, status, config_version,")
                    .append(" issued_at, issued_day, eligible_from, due_at, window_id, employee_id, called_at, call_count, serving_started_at,")
                    .append(" completed_at, close_reason, updated_at, version) VALUES ('").append(id).append("','b01','").append(token).append("','")
                    .append(display).append("','").append(SERVICE_IDS.get(j.service())).append("','").append(j.source()).append("','")
                    .append(j.category()).append("','done',1,'").append(j.arrival()).append("','").append(day).append("','").append(j.arrival())
                    .append("','").append(j.due()).append("','").append(window).append("','").append(employee).append("','").append(called)
                    .append("',1,'").append(started).append("','").append(completed).append("','completed','").append(completed)
                    .append("',3) ON CONFLICT DO NOTHING;\n");
            event(sql, id, "ticket.created", null, "waiting", "client", j.arrival(), order, 0);
            event(sql, id, "ticket.called", "waiting", "called", "operator", called, order, 1);
            event(sql, id, "ticket.completed", "called", "done", "operator", completed, order, 2);
        }

        static void event(StringBuilder sql, UUID ticket, String type, String from, String to, String actor, Instant at, int order, int step) {
            var eventId = UUID.nameUUIDFromBytes(("e" + ticket + step).getBytes(StandardCharsets.UTF_8));
            // A ticket may fail to insert (ux_ticket_number conflict with existing tickets of the day):
            // write events only for tickets present in the DB, otherwise the FK fails.
            sql.append("INSERT INTO ticket_events(id, ticket_id, event_type, from_status, to_status, actor_type, payload, occurred_at) SELECT '")
                    .append(eventId).append("'::uuid,'").append(ticket).append("'::uuid,'").append(type).append("',")
                    .append(from == null ? "NULL::text" : "'" + from + "'").append(",'").append(to).append("','").append(actor)
                    .append("','{\"synthetic\": true}'::jsonb,'").append(at).append("'::timestamptz WHERE EXISTS (SELECT 1 FROM tickets WHERE id='")
                    .append(ticket).append("') ON CONFLICT DO NOTHING;\n");
        }

        static String windowEvent(String window, String type, String payload, Instant at, LocalDate day, int w) {
            var id = UUID.nameUUIDFromBytes(("w" + window + type + day).getBytes(StandardCharsets.UTF_8));
            return "INSERT INTO window_events(id, window_id, event_type, payload, occurred_at) VALUES ('" + id + "','" + window + "','" + type
                    + "','" + payload + "','" + at + "') ON CONFLICT DO NOTHING;\n";
        }
    }
}
