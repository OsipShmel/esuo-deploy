package honey.road.swarm;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * One virtual branch: what a real node knows about itself to send heartbeats and events. State is touched only by
 * the simulation thread; {@link #configVersion} is also written by the NATS dispatcher, hence volatile.
 */
public final class VirtualBranch {
    /** Russian time zones east to west — regions spread over them like the real network. */
    static final String[] ZONES = {"Europe/Moscow", "Asia/Yekaterinburg", "Asia/Novosibirsk", "Asia/Vladivostok",
            "Europe/Samara", "Asia/Krasnoyarsk", "Asia/Irkutsk", "Europe/Kaliningrad", "Asia/Omsk", "Asia/Yakutsk",
            "Asia/Magadan", "Asia/Kamchatka"};

    final String id;
    final int region;
    /** JetStream domain of the regional leaf holding this branch's events; set once the leaf is connected. */
    String eventsVia;
    String natsUrl;
    final ZoneId zone;
    final String name;
    final int clientsPerDay;
    final UUID[] windowIds;
    final long[] windowFreeAt;                  // epoch ms of virtual time when each window is free again
    final boolean[] windowOpen;
    final SplittableRandom rng;
    final Instant startedAt;
    final Deque<Long> waiting = new ArrayDeque<>();   // issued_at (ms) of waiting tickets, oldest first

    LocalDate day;
    DayProfile profile = DayProfile.CLOSED;
    final int[] seq = new int[Services.ALL.length];
    long eventsEmitted;
    volatile int configVersion;

    VirtualBranch(int index, int regions, int clientsPerDay, long seed, Instant startedAt) {
        this.region = index % regions + 1;
        this.id = id(region, index / regions + 1);
        this.zone = ZoneId.of(ZONES[(region - 1) % ZONES.length]);
        this.name = "Виртуальное отделение " + id;
        this.rng = new SplittableRandom(seed * 1_000_003L + index);
        // Branches differ in size: a lognormal-ish spread of the daily flow, windows follow the flow.
        double size = Math.exp(rng.nextGaussian() * 0.45);
        this.clientsPerDay = (int) Math.max(20, Math.min(clientsPerDay * 2.5, Math.round(clientsPerDay * size)));
        int windows = Math.max(1, Math.min(6, (int) Math.round(this.clientsPerDay / 60.0)));
        this.windowIds = new UUID[windows];
        for (int w = 0; w < windows; w++) {
            windowIds[w] = UUID.nameUUIDFromBytes((id + "/window/" + w).getBytes(StandardCharsets.UTF_8));
        }
        this.windowFreeAt = new long[windows];
        this.windowOpen = new boolean[windows];
        this.startedAt = startedAt;
    }

    /** {@code s1-00042}: the region in the id makes a regional dashboard trivial to filter. */
    static String id(int region, int number) {
        return "s" + region + "-" + String.format("%05d", number);
    }

    int windowsOpen() {
        int n = 0;
        for (boolean open : windowOpen) { if (open) { n++; } }
        return n;
    }

    String windowName(int w) {
        return "Окно " + (w + 1);
    }
}
