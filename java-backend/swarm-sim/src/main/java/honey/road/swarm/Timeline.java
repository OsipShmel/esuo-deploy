package honey.road.swarm;

import honey.road.contracts.event.Actor;
import honey.road.contracts.event.EventEnvelope;
import honey.road.contracts.event.EventTypes;
import honey.road.contracts.event.TokenHash;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.LongConsumer;

/**
 * Virtual time of the whole swarm: one priority queue of timed actions for every branch. Arrivals follow the day curve
 * (non-homogeneous Poisson by thinning); each ticket is placed on the window that frees up first, so its whole life
 * — created, called, serving, completed (or cancelled / no-show) — is known at arrival and scheduled at once.
 *
 * <p>Not thread-safe: driven by the simulation thread only. Emits through {@link EventSink}, so tests run it without
 * NATS and with any virtual clock.
 */
final class Timeline {
    static final long MINUTE = 60_000;
    static final double PENSIONER_SHARE = 0.25;
    static final double QR_SHARE = 0.7;
    static final double CANCEL_SHARE = 0.02;
    static final double NO_SHOW_SHARE = 0.03;
    static final long STOP_ARRIVALS_BEFORE_CLOSE = 15 * MINUTE;

    private record Action(long at, long seq, LongConsumer run) { }

    /** A ticket from arrival to its terminal event; everything the node would put into the event data. */
    private static final class Ticket {
        final VirtualBranch branch;
        final UUID id;
        final UUID token;
        final int service;
        final String displayNumber;
        final String source;
        final String category;
        final long issuedAt;
        final long dueAt;
        final int window;
        Long calledAt;
        String status = "waiting";
        long version;
        int callCount;

        Ticket(VirtualBranch branch, UUID id, UUID token, int service, String displayNumber, String source,
               String category, long issuedAt, long dueAt, int window) {
            this.branch = branch; this.id = id; this.token = token; this.service = service;
            this.displayNumber = displayNumber; this.source = source; this.category = category;
            this.issuedAt = issuedAt; this.dueAt = dueAt; this.window = window;
        }
    }

    private final PriorityQueue<Action> queue = new PriorityQueue<>(
            (a, b) -> a.at != b.at ? Long.compare(a.at, b.at) : Long.compare(a.seq, b.seq));
    private final List<VirtualBranch> branches;
    private final EventSink sink;
    private final double peak;
    private final boolean notifications;
    private final SplittableRandom ids;
    private final long[][] windowVersion;
    private long seq;
    private long processed;

    /**
     * @param idSeed seed of event and ticket ids — separate from the flow model so that two runs with the same model
     *               seed do not collide in the center's dedup ({@code ON CONFLICT (event_id)})
     */
    Timeline(List<VirtualBranch> branches, EventSink sink, double peak, boolean notifications, long idSeed) {
        this.branches = branches;
        this.sink = sink;
        this.peak = peak;
        this.notifications = notifications;
        this.ids = new SplittableRandom(idSeed);
        this.windowVersion = new long[branches.size()][];
        for (int i = 0; i < branches.size(); i++) { windowVersion[i] = new long[branches.get(i).windowIds.length]; }
    }

    /** Plans "today" of every branch in its own time zone, from {@code now} on. */
    void start(Instant now) {
        for (int i = 0; i < branches.size(); i++) {
            var b = branches.get(i);
            int index = i;
            planDay(b, index, LocalDate.ofInstant(now, b.zone), now.toEpochMilli(), true);
        }
    }

    /** Runs every action due by {@code now}; returns how many ran. */
    int advanceTo(Instant now) {
        long limit = now.toEpochMilli();
        int n = 0;
        while (!queue.isEmpty() && queue.peek().at <= limit) {
            var a = queue.poll();
            a.run.accept(a.at);
            n++;
        }
        processed += n;
        return n;
    }

    int pending() { return queue.size(); }

    long processed() { return processed; }

    private void at(long time, LongConsumer action) {
        queue.add(new Action(time, seq++, action));
    }

    // ------------------------------------------------------------------ day

    /**
     * @param resumed the run starts while this day is already going: windows that opened before the start are simply
     *                open (no {@code window.opened} now) — otherwise a noon start would emit every window of the
     *                network in the first second, a burst no real network ever produces
     */
    private void planDay(VirtualBranch b, int index, LocalDate day, long now, boolean resumed) {
        b.day = day;
        b.profile = DayProfile.of(day.getDayOfWeek());
        java.util.Arrays.fill(b.seq, 0);
        long nextMidnight = day.plusDays(1).atStartOfDay(b.zone).toInstant().toEpochMilli();
        at(nextMidnight, t -> planDay(b, index, day.plusDays(1), t, false));
        if (b.profile.closed()) { return; }

        long open = ZonedDateTime.of(day, b.profile.open(), b.zone).toInstant().toEpochMilli();
        long close = ZonedDateTime.of(day, b.profile.close(), b.zone).toInstant().toEpochMilli();
        if (close <= now) { return; }
        for (int w = 0; w < b.windowIds.length; w++) {
            int window = w;
            b.windowFreeAt[w] = Math.max(open, now);
            if (resumed && open < now) {
                b.windowOpen[w] = true;
            } else {
                at(Math.max(open, now), t -> openWindow(b, index, window, t));
            }
            at(close, t -> closeWindow(b, index, window, t));
        }
        nextArrival(b, Math.max(open, now), close - STOP_ARRIVALS_BEFORE_CLOSE);
    }

    /** Thinning: candidates at the day's top hourly rate, kept with probability rate(t) / top rate. */
    private void nextArrival(VirtualBranch b, long from, long until) {
        double top = b.clientsPerDay * b.profile.flow() * peak * b.profile.maxHourShare() / 3_600_000.0;   // per ms
        if (top <= 0) { return; }
        long t = from + (long) Math.ceil(-Math.log(1 - b.rng.nextDouble()) / top);
        if (t >= until) { return; }
        at(t, now -> {
            int hour = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), b.zone).getHour();
            double rate = b.clientsPerDay * b.profile.flow() * peak * b.profile.hourShare(hour) / 3_600_000.0;
            if (b.rng.nextDouble() < rate / top) { arrive(b, now); }
            nextArrival(b, now, until);
        });
    }

    // ------------------------------------------------------------------ windows

    private void openWindow(VirtualBranch b, int index, int w, long t) {
        if (b.windowOpen[w]) { return; }
        b.windowOpen[w] = true;
        windowEvent(b, index, w, EventTypes.WINDOW_OPENED, "open", t);
    }

    /** Graceful close: the window finishes whoever it already took. */
    private void closeWindow(VirtualBranch b, int index, int w, long t) {
        if (!b.windowOpen[w]) { return; }
        if (b.windowFreeAt[w] > t) {
            at(b.windowFreeAt[w], later -> closeWindow(b, index, w, later));
            return;
        }
        b.windowOpen[w] = false;
        windowEvent(b, index, w, EventTypes.WINDOW_CLOSED, "closed", t);
    }

    private void windowEvent(VirtualBranch b, int index, int w, String type, String status, long t) {
        var data = new LinkedHashMap<String, Object>();
        data.put("window_id", b.windowIds[w]);
        data.put("window_name", b.windowName(w));
        data.put("status", status);
        data.put("employee_id", employee(b, w));
        emit(b, new EventEnvelope(eventId(t), type, EventEnvelope.SCHEMA_VERSION, b.id, EventTypes.AGGREGATE_WINDOW,
                b.windowIds[w], ++windowVersion[index][w], Instant.ofEpochMilli(t), version(b),
                Actor.operator(employee(b, w).toString()), data));
    }

    // ------------------------------------------------------------------ tickets

    private void arrive(VirtualBranch b, long t) {
        var rng = b.rng;
        int service = Services.pick(rng.nextDouble());
        String category = rng.nextDouble() < PENSIONER_SHARE ? "pensioner" : "default";
        String source = rng.nextDouble() < QR_SHARE ? "qr" : "walkin";
        int w = firstFreeWindow(b);
        long callAt = Math.max(t + 5_000, b.windowFreeAt[w]);
        var ticket = new Ticket(b, uuid(t), uuid(t), service,
                Services.ALL[service].prefix() + "-" + String.format("%03d", ++b.seq[service]), source, category, t,
                t + ("pensioner".equals(category) ? 15 : 20) * MINUTE, w);
        b.waiting.addLast(t);
        ticketEvent(ticket, EventTypes.TICKET_CREATED, null, t,
                "qr".equals(source) ? Actor.client() : Actor.operator(employee(b, w).toString()));

        double outcome = rng.nextDouble();
        if (outcome < CANCEL_SHARE && callAt - t > 2 * MINUTE) {
            long cancelAt = t + MINUTE + (long) (rng.nextDouble() * (callAt - t - 2 * MINUTE));
            at(cancelAt, now -> {
                b.waiting.removeFirstOccurrence(ticket.issuedAt);
                step(ticket, EventTypes.TICKET_CANCELLED, "cancelled", now, Actor.client());
            });
            return;                                          // the window never sees this client
        }
        if (notifications && callAt - t > 6 * MINUTE) {
            at(callAt - 5 * MINUTE, now -> notification(ticket, now, callAt));
        }
        at(callAt, now -> {
            b.waiting.removeFirstOccurrence(ticket.issuedAt);
            ticket.calledAt = now;
            ticket.callCount++;
            step(ticket, EventTypes.TICKET_CALLED, "called", now, operator(ticket));
        });
        if (outcome < CANCEL_SHARE + NO_SHOW_SHARE) {
            long noShowAt = callAt + 3 * MINUTE;
            b.windowFreeAt[w] = noShowAt;
            at(noShowAt, now -> step(ticket, EventTypes.TICKET_NO_SHOW, "no_show", now, operator(ticket)));
            return;
        }
        long servingAt = callAt + 20_000 + (long) (rng.nextDouble() * 40_000);
        long doneAt = servingAt + serviceMillis(rng, service);
        b.windowFreeAt[w] = doneAt;
        at(servingAt, now -> step(ticket, EventTypes.TICKET_SERVING, "serving", now, operator(ticket)));
        at(doneAt, now -> step(ticket, EventTypes.TICKET_COMPLETED, "done", now, operator(ticket)));
    }

    /** Service time: exponential around 4 minutes, finance longer, parcels shorter; 45 s … 30 min. */
    static long serviceMillis(SplittableRandom rng, int service) {
        double factor = switch (Services.ALL[service].code()) {
            case "finance" -> 1.4;
            case "parcel" -> 0.8;
            default -> 1.0;
        };
        double sec = -Math.log(1 - rng.nextDouble()) * 240 * factor;
        return (long) (Math.max(45, Math.min(1800, sec)) * 1000);
    }

    private static int firstFreeWindow(VirtualBranch b) {
        int best = -1;
        for (int w = 0; w < b.windowFreeAt.length; w++) {
            if (b.windowOpen[w] && (best < 0 || b.windowFreeAt[w] < b.windowFreeAt[best])) { best = w; }
        }
        if (best >= 0) { return best; }
        best = 0;                                            // nobody open yet: the first window to open takes it
        for (int w = 1; w < b.windowFreeAt.length; w++) {
            if (b.windowFreeAt[w] < b.windowFreeAt[best]) { best = w; }
        }
        return best;
    }

    private void step(Ticket k, String type, String status, long t, Actor actor) {
        String from = k.status;
        k.status = status;
        ticketEvent(k, type, from, t, actor);
    }

    private void ticketEvent(Ticket k, String type, String fromStatus, long t, Actor actor) {
        var b = k.branch;
        var data = new LinkedHashMap<String, Object>();
        data.put("display_number", k.displayNumber);
        data.put("token_hash", TokenHash.of(k.token));
        data.put("source", k.source);
        data.put("service_id", Services.ALL[k.service].id());
        data.put("window_id", k.calledAt == null ? null : b.windowIds[k.window]);
        data.put("target_window_id", null);
        data.put("from_status", fromStatus);
        data.put("status", k.status);
        data.put("client_category", k.category);
        data.put("slot_start", null);
        data.put("eligible_from", Instant.ofEpochMilli(k.issuedAt));
        data.put("due_at", Instant.ofEpochMilli(k.dueAt));
        data.put("issued_at", Instant.ofEpochMilli(k.issuedAt));
        data.put("called_at", k.calledAt == null ? null : Instant.ofEpochMilli(k.calledAt));
        data.put("call_count", k.callCount);
        if (k.calledAt != null) { data.put("window_name", b.windowName(k.window)); }
        emit(b, new EventEnvelope(eventId(t), type, EventEnvelope.SCHEMA_VERSION, b.id, EventTypes.AGGREGATE_TICKET,
                k.id, ++k.version, Instant.ofEpochMilli(t), version(b), actor, data));
    }

    private void notification(Ticket k, long t, long callAt) {
        if (!"waiting".equals(k.status)) { return; }
        var b = k.branch;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kind", "approaching");
        data.put("dedup_key", k.id + ":approaching");
        data.put("ticket_id", k.id);
        data.put("token_hash", TokenHash.of(k.token));
        data.put("display_number", k.displayNumber);
        data.put("service_id", Services.ALL[k.service].id());
        data.put("status", k.status);
        data.put("position", 1);
        data.put("eta", Instant.ofEpochMilli(callAt));
        data.put("eta_confidence", "medium");
        emit(b, new EventEnvelope(eventId(t), EventTypes.NOTIFICATION_REQUESTED, EventEnvelope.SCHEMA_VERSION, b.id,
                EventTypes.AGGREGATE_TICKET, k.id, k.version, Instant.ofEpochMilli(t), version(b), Actor.system(), data));
    }

    private void emit(VirtualBranch b, EventEnvelope e) {
        b.eventsEmitted++;
        sink.event(b, e);
    }

    private static Actor operator(Ticket k) {
        return Actor.operator(employee(k.branch, k.window).toString());
    }

    /** The operator at window {@code w}: stable per branch and window. */
    static UUID employee(VirtualBranch b, int w) {
        return UUID.nameUUIDFromBytes((b.id + "/employee/" + w).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Integer version(VirtualBranch b) {
        return b.configVersion == 0 ? null : b.configVersion;
    }

    /** UUIDv7 like the node's {@code UuidGenerator}: time in the high bits keeps ids ordered. */
    private UUID eventId(long t) {
        long high = ((t & 0xffffffffffffL) << 16) | 0x7000L | ids.nextInt(4096);
        long low = (ids.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(high, low);
    }

    private UUID uuid(long t) {
        return eventId(t);
    }
}
