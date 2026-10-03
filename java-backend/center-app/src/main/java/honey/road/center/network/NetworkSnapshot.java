package honey.road.center.network;

import honey.road.center.config.CenterProperties;
import honey.road.center.monitoring.BranchAlerts;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * One picture of the network for every screen and every poll (scale design 8.2): for each branch whether it is on line,
 * what is wrong and how badly, and the sums of the network and of each region. At 250 000 branches judging them costs
 * the database seconds, so the picture is taken when someone asks and the last one is too old — three times the time
 * it took, at least a second, at most {@code snapshot-max-age} — and shared by everyone meanwhile: nobody looking, no
 * load. Rows come from a cursor: the network is never one list of heartbeats in memory.
 */
@Component
public class NetworkSnapshot {
    /** Branches on their own leaf — the real nodes — in filters and region sums. */
    public static final String OWN = "own";
    private static final Duration MIN_AGE = Duration.ofSeconds(1);

    /** A branch as the lists need it: who, where, on line or not, what is wrong and how badly. */
    public record Entry(String id, String name, @Nullable String region, boolean online, int severity,
                        List<String> anomalies) {
        boolean matches(String needle) {
            return needle.isEmpty() || id.toLowerCase(Locale.ROOT).contains(needle) || name.toLowerCase(Locale.ROOT).contains(needle);
        }

        String regionKey() {
            return region == null ? OWN : region;
        }
    }

    static final Comparator<Entry> WORST_FIRST = Comparator.comparingInt(Entry::severity).reversed().thenComparing(Entry::id);

    /**
     * Sums of a group of branches — the network, a region. Queue numbers are of the branches on line: those of a
     * branch out of touch are as old as the touch.
     */
    public static final class Sums {
        private int total, online, problems;
        private long waiting, overdue, windowsOpen;
        private final Map<String, Integer> loadLevels = new TreeMap<>();
        private final Map<String, Integer> anomalies = new TreeMap<>();
        private @Nullable Entry worst;

        void add(BranchStatus s, Entry e) {
            total++;
            loadLevels.merge(s.loadLevel() == null ? "unknown" : s.loadLevel(), 1, Integer::sum);
            if (e.online()) {
                online++;
                waiting += s.waiting();
                overdue += s.overdue();
                windowsOpen += s.windowsOpen();
            }
            if (!e.anomalies().isEmpty()) {
                problems++;
                e.anomalies().forEach(a -> anomalies.merge(a, 1, Integer::sum));
                if (worst == null || WORST_FIRST.compare(e, worst) < 0) {
                    worst = e;
                }
            }
        }

        public int total() { return total; }
        public int online() { return online; }
        public int problems() { return problems; }
        public Map<String, Integer> loadLevels() { return loadLevels; }
        public Map<String, Integer> anomalies() { return anomalies; }

        /** The tile of a region and the head of its page: counts, queue, anomalies by type, the worst branch. */
        public Map<String, Object> view(@Nullable String eventsVia) {
            var m = new LinkedHashMap<String, Object>();
            m.put("events_via", eventsVia);
            m.put("total", total);
            m.put("online", online);
            m.put("problems", problems);
            m.put("waiting", waiting);
            m.put("overdue", overdue);
            m.put("windows_open", windowsOpen);
            m.put("load_levels", loadLevels);
            m.put("anomalies", anomalies);
            m.put("worst", worst == null ? null : worst.id());
            return m;
        }
    }

    /**
     * @param entries  every branch, by id
     * @param regions  by {@code events_via}; {@link #OWN} for the branches on their own leaf
     * @param problems branches with an anomaly, worst first
     */
    public record View(Instant generatedAt, List<Entry> entries, Sums network, Map<String, Sums> regions,
                       List<Entry> problems) {
        /**
         * Branches by a search, a status, a region and an anomaly; worst first for {@code problems}, by id otherwise.
         * Blank arguments do not filter.
         */
        public List<Entry> select(@Nullable String q, @Nullable String status, @Nullable String region,
                                  @Nullable String anomaly) {
            var needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
            boolean worstFirst = "problems".equals(status) || (anomaly != null && !anomaly.isBlank());
            var out = new ArrayList<Entry>();
            for (var e : worstFirst ? problems : entries) {
                boolean keep = e.matches(needle)
                        && (region == null || region.isBlank() || region.equals(e.regionKey()))
                        && (anomaly == null || anomaly.isBlank() || e.anomalies().contains(anomaly))
                        && switch (status == null ? "all" : status) {
                            case "online" -> e.online();
                            case "offline" -> !e.online();
                            case "problems" -> !e.anomalies().isEmpty();
                            default -> true;
                        };
                if (keep) {
                    out.add(e);
                }
            }
            return out;
        }
    }

    private final BranchRegistry registry;
    private final BranchAlerts alerts;
    private final Clock clock;
    private final Duration offlineAfter;
    private final Duration maxAge;
    private final Timer buildTimer;
    private @Nullable View view;
    private Duration lastBuild = Duration.ZERO;

    NetworkSnapshot(BranchRegistry registry, BranchAlerts alerts, CenterProperties props, Clock clock, MeterRegistry meters) {
        this.registry = registry; this.alerts = alerts; this.clock = clock;
        this.offlineAfter = props.offlineAfter(); this.maxAge = props.network().snapshotMaxAge();
        this.buildTimer = Timer.builder("esuo.network.snapshot").description("One picture of the whole network")
                .register(meters);
    }

    /** The current picture; one caller builds a new one when it is too old, the others wait for it. */
    public synchronized View current() {
        var now = clock.instant();
        if (view == null || Duration.between(view.generatedAt(), now).compareTo(age()) >= 0) {
            long started = System.nanoTime();
            view = build(now);
            lastBuild = Duration.ofNanos(System.nanoTime() - started);
            buildTimer.record(lastBuild);
        }
        return view;
    }

    private Duration age() {
        var byCost = lastBuild.multipliedBy(3);
        var wanted = byCost.compareTo(MIN_AGE) < 0 ? MIN_AGE : byCost;
        return wanted.compareTo(maxAge) > 0 ? maxAge : wanted;
    }

    private View build(Instant now) {
        var alertsByBranch = alerts.byBranch();
        var entries = new ArrayList<Entry>();
        var network = new Sums();
        var regions = new TreeMap<String, Sums>();
        var problems = new ArrayList<Entry>();
        registry.eachStatus(s -> {
            var branchAlerts = alertsByBranch.getOrDefault(s.id(), List.of());
            var anomalies = NetworkAnomalies.of(s, branchAlerts, now, offlineAfter);
            var e = new Entry(s.id(), s.name(), s.eventsVia(), NetworkAnomalies.online(s.lastHeartbeatAt(), now, offlineAfter),
                    NetworkAnomalies.severity(anomalies, branchAlerts, s.overdue()),
                    anomalies.isEmpty() ? List.of() : List.copyOf(anomalies));
            entries.add(e);
            network.add(s, e);
            regions.computeIfAbsent(e.regionKey(), k -> new Sums()).add(s, e);
            if (!anomalies.isEmpty()) {
                problems.add(e);
            }
        });
        problems.sort(WORST_FIRST);
        return new View(now, Collections.unmodifiableList(entries), network, Collections.unmodifiableMap(regions),
                Collections.unmodifiableList(problems));
    }
}
