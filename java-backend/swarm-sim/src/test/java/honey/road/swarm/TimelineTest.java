package honey.road.swarm;

import honey.road.contracts.event.EventEnvelope;
import honey.road.contracts.event.EventTypes;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** One virtual day of the swarm without NATS: what reaches the center must look like a real branch's day. */
class TimelineTest {
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    /** Monday 2026-10-05, 07:00 Moscow: before opening everywhere west of the Urals. */
    private static final Instant MONDAY_MORNING = ZonedDateTime.of(LocalDateTime.of(2026, 10, 5, 7, 0), MOSCOW).toInstant();
    private static final Set<String> TERMINAL = Set.of(EventTypes.TICKET_COMPLETED, EventTypes.TICKET_CANCELLED,
            EventTypes.TICKET_NO_SHOW);

    static final class Recorder implements EventSink {
        final List<EventEnvelope> events = new ArrayList<>();

        @Override public void event(VirtualBranch branch, EventEnvelope envelope) { events.add(envelope); }

        @Override public void heartbeat(VirtualBranch branch, Map<String, Object> heartbeat) { }
    }

    private static Recorder runDay(int branches, int regions, double peak, long seed, long idSeed, Instant start) {
        var sink = new Recorder();
        var list = SwarmMain.branches(branches, regions, 150, seed, start);
        var timeline = new Timeline(list, sink, peak, true, idSeed);
        timeline.start(start);
        timeline.advanceTo(start.plusSeconds(24 * 3600));
        return sink;
    }

    private static Map<UUID, List<EventEnvelope>> byTicket(Recorder r) {
        return r.events.stream().filter(e -> EventTypes.AGGREGATE_TICKET.equals(e.aggregateType())
                        && !EventTypes.NOTIFICATION_REQUESTED.equals(e.eventType()))
                .collect(Collectors.groupingBy(EventEnvelope::aggregateId, LinkedHashMap::new, Collectors.toList()));
    }

    @Test
    void dailyFlowMatchesTheBranchSizes() {
        var start = MONDAY_MORNING;
        var branches = SwarmMain.branches(40, 1, 150, 7, start);
        long expected = branches.stream().mapToLong(b -> b.clientsPerDay).sum();
        var created = runDay(40, 1, 1, 7, 1, start).events.stream()
                .filter(e -> EventTypes.TICKET_CREATED.equals(e.eventType())).count();
        // Poisson over ~6 000 clients: a few per cent; the opening hour before 08:00 is not lost since we start at 07:00.
        assertThat((double) created / expected).isBetween(0.9, 1.1);
    }

    @Test
    void everyTicketLivesAWholeLifeInOrder() {
        // Monday's tickets only: east of Moscow Tuesday opens before the 24 h horizon and is cut off mid-flight.
        var tickets = byTicket(runDay(20, 2, 1, 11, 1, MONDAY_MORNING));
        tickets.values().removeIf(events -> events.getFirst().occurredAt().isAfter(MONDAY_MORNING.plusSeconds(16 * 3600)));
        assertThat(tickets).hasSizeGreaterThan(1000);
        for (var events : tickets.values()) {
            assertThat(events.getFirst().eventType()).isEqualTo(EventTypes.TICKET_CREATED);
            assertThat(TERMINAL).contains(events.getLast().eventType());
            assertThat(events.stream().filter(e -> TERMINAL.contains(e.eventType()))).hasSize(1);
            for (int i = 0; i < events.size(); i++) {
                assertThat(events.get(i).aggregateVersion()).isEqualTo(i + 1);          // like ticket.version on a node
                if (i > 0) { assertThat(events.get(i).occurredAt()).isAfterOrEqualTo(events.get(i - 1).occurredAt()); }
            }
            var types = events.stream().map(EventEnvelope::eventType).toList();
            if (types.contains(EventTypes.TICKET_COMPLETED)) {
                assertThat(types).containsExactly(EventTypes.TICKET_CREATED, EventTypes.TICKET_CALLED,
                        EventTypes.TICKET_SERVING, EventTypes.TICKET_COMPLETED);
            }
            assertThat(events.getLast().data()).doesNotContainKey("public_token");
        }
        var outcomes = tickets.values().stream().collect(Collectors.groupingBy(e -> e.getLast().eventType(), Collectors.counting()));
        long all = tickets.size();
        assertThat((double) outcomes.get(EventTypes.TICKET_COMPLETED) / all).isGreaterThan(0.9);
        assertThat(outcomes).containsKeys(EventTypes.TICKET_NO_SHOW);
    }

    @Test
    void ticketsArriveOnlyDuringOpeningHoursOfTheirTimeZone() {
        var start = MONDAY_MORNING.minusSeconds(12 * 3600);       // Sunday evening: every zone gets its whole Monday
        var r = runDay(24, 12, 1, 3, 1, start);
        var zones = new HashMap<String, ZoneId>();
        SwarmMain.branches(24, 12, 150, 3, start).forEach(b -> zones.put(b.id, b.zone));
        assertThat(zones.values().stream().distinct().count()).isEqualTo(12);
        r.events.stream().filter(e -> EventTypes.TICKET_CREATED.equals(e.eventType())).forEach(e -> {
            var local = e.occurredAt().atZone(zones.get(e.branchId())).toLocalTime();
            assertThat(local).isAfterOrEqualTo(DayProfile.WEEKDAY.open())
                    .isBefore(DayProfile.WEEKDAY.close().minusMinutes(15));
        });
    }

    @Test
    void windowsOpenOnceAndCloseAfterTheirLastClient() {
        var r = runDay(10, 1, 1, 5, 1, MONDAY_MORNING);
        var windows = r.events.stream().filter(e -> EventTypes.AGGREGATE_WINDOW.equals(e.aggregateType()))
                .collect(Collectors.groupingBy(EventEnvelope::aggregateId));
        assertThat(windows).isNotEmpty();
        var lastDone = new HashMap<UUID, Instant>();
        r.events.stream().filter(e -> EventTypes.TICKET_COMPLETED.equals(e.eventType()))
                .forEach(e -> lastDone.merge((UUID) e.data().get("window_id"), e.occurredAt(), (a, b) -> a.isAfter(b) ? a : b));
        windows.forEach((id, events) -> {
            assertThat(events).extracting(EventEnvelope::eventType)
                    .containsExactly(EventTypes.WINDOW_OPENED, EventTypes.WINDOW_CLOSED);
            if (lastDone.containsKey(id)) { assertThat(events.getLast().occurredAt()).isAfterOrEqualTo(lastDone.get(id)); }
        });
    }

    @Test
    void sameSeedSameDayAndFreshEventIdsPerRun() {
        var a = runDay(5, 2, 1, 42, 1, MONDAY_MORNING).events;
        var b = runDay(5, 2, 1, 42, 2, MONDAY_MORNING).events;
        assertThat(b).hasSameSizeAs(a);
        for (int i = 0; i < a.size(); i++) {
            assertThat(b.get(i).eventType()).isEqualTo(a.get(i).eventType());
            assertThat(b.get(i).occurredAt()).isEqualTo(a.get(i).occurredAt());
            assertThat(b.get(i).data().get("display_number")).isEqualTo(a.get(i).data().get("display_number"));
        }
        // A second load run must not collide with the first in the center's ON CONFLICT (event_id).
        assertThat(b.getFirst().eventId()).isNotEqualTo(a.getFirst().eventId());
    }

    @Test
    void peakMultipliesTheFlow() {
        long normal = runDay(10, 1, 1, 9, 1, MONDAY_MORNING).events.stream()
                .filter(e -> EventTypes.TICKET_CREATED.equals(e.eventType())).count();
        long peak = runDay(10, 1, 3, 9, 1, MONDAY_MORNING).events.stream()
                .filter(e -> EventTypes.TICKET_CREATED.equals(e.eventType())).count();
        assertThat((double) peak / normal).isBetween(2.7, 3.3);
    }

    @Test
    void aNoonStartFindsWindowsAlreadyOpenInsteadOfOpeningThemAll() {
        var noon = MONDAY_MORNING.plusSeconds(5 * 3600);           // 12:00 Moscow
        var r = new Recorder();
        var branches = SwarmMain.branches(20, 1, 150, 2, noon);
        var timeline = new Timeline(branches, r, 1, false, 1);
        timeline.start(noon);
        timeline.advanceTo(noon.plusSeconds(60));
        assertThat(r.events).noneMatch(e -> EventTypes.WINDOW_OPENED.equals(e.eventType()));
        assertThat(branches).allMatch(b -> b.windowsOpen() == b.windowIds.length);
    }

    @Test
    void sundayIsQuiet() {
        var sunday = MONDAY_MORNING.minusSeconds(24 * 3600);
        var r = new Recorder();
        var timeline = new Timeline(SwarmMain.branches(5, 1, 150, 1, sunday), r, 1, false, 1);
        timeline.start(sunday);
        timeline.advanceTo(sunday.plusSeconds(16 * 3600));       // until 23:00 Sunday
        assertThat(r.events).isEmpty();
    }

    @Test
    void notificationsComeBeforeTheCallAndOnlyWhenAsked() {
        var r = runDay(10, 1, 3, 4, 1, MONDAY_MORNING);
        var called = new HashMap<UUID, Instant>();
        r.events.stream().filter(e -> EventTypes.TICKET_CALLED.equals(e.eventType()))
                .forEach(e -> called.put(e.aggregateId(), e.occurredAt()));
        var notes = r.events.stream().filter(e -> EventTypes.NOTIFICATION_REQUESTED.equals(e.eventType())).toList();
        assertThat(notes).isNotEmpty();
        notes.forEach(n -> {
            assertThat(n.data()).containsEntry("kind", "approaching").containsKey("dedup_key");
            if (called.containsKey(n.aggregateId())) { assertThat(n.occurredAt()).isBefore(called.get(n.aggregateId())); }
        });

        var quiet = new Recorder();
        var t = new Timeline(SwarmMain.branches(10, 1, 150, 4, MONDAY_MORNING), quiet, 3, false, 1);
        t.start(MONDAY_MORNING);
        t.advanceTo(MONDAY_MORNING.plusSeconds(24 * 3600));
        assertThat(quiet.events).noneMatch(e -> EventTypes.NOTIFICATION_REQUESTED.equals(e.eventType()));
    }
}
