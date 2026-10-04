package honey.road.swarm;

import honey.road.contracts.event.EventTypes;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What leaves the process: JSON the center can read, heartbeat fields, ids, schedule and options. */
class SwarmFormatsTest {
    private static final Instant NOW = Instant.parse("2026-10-05T09:00:00Z");

    @Test
    void eventJsonIsWhatTheCenterIngestReads() {
        var sink = new TimelineTest.Recorder();
        var timeline = new Timeline(SwarmMain.branches(1, 1, 400, 1, NOW), sink, 1, false, 1);
        timeline.start(NOW);
        timeline.advanceTo(NOW.plusSeconds(3600));
        var created = sink.events.stream().filter(e -> EventTypes.TICKET_CREATED.equals(e.eventType())).findFirst().orElseThrow();
        var json = NatsSink.mapper().readTree(NatsSink.mapper().writeValueAsString(created));
        assertThat(json.path("event_id").asString()).isEqualTo(created.eventId().toString());
        assertThat(json.path("branch_id").asString()).isEqualTo("s1-00001");
        assertThat(json.path("aggregate_type").asString()).isEqualTo("ticket");
        assertThat(Instant.parse(json.path("occurred_at").asString())).isEqualTo(created.occurredAt());   // EventIngest
        assertThat(json.path("data").path("display_number").asString()).startsWith(created.data().get("display_number").toString());
        assertThat(created.subject()).isEqualTo("branch.s1-00001.evt.ticket.created");
        assertThat(created.eventId().version()).isEqualTo(7);
    }

    @Test
    void heartbeatCarriesQueueEventsViaAndSys() {
        var b = SwarmMain.branches(3, 2, 150, 1, NOW).get(2);
        b.eventsVia = "region-1";
        b.waiting.add(NOW.minusSeconds(25 * 60).toEpochMilli());
        b.waiting.add(NOW.minusSeconds(60).toEpochMilli());
        b.configVersion = 7;
        var hb = Heartbeats.snapshot(b, NOW, NOW, 60);
        assertThat(b.id).isEqualTo("s1-00002");                    // third branch of two regions: region 1, number 2
        assertThat(hb).containsEntry("branch_id", "s1-00002").containsEntry("events_via", "region-1")
                .containsEntry("waiting", 2).containsEntry("overdue", 1L).containsEntry("oldest_wait_sec", 1500L)
                .containsEntry("config_version", 7).containsEntry("demo_rate", 60.0)
                .containsKeys("services", "windows_open", "load_level", "timezone");
        @SuppressWarnings("unchecked") var sys = (Map<String, Object>) hb.get("sys");
        assertThat(sys).containsKeys("cpu_system", "heap_used_mb", "db_pool_pending", "http_p95_ms", "disk_free_mb",
                "leaf_connected", "sent_at_system", "uptime_sec", "app_version");
        assertThat(NatsSink.mapper().writeValueAsBytes(hb).length).isLessThan(2500);   // ~2 KB like a node's heartbeat
    }

    @Test
    void heartbeatsAreSpreadEvenlyOverThePeriod() {
        var schedule = new HeartbeatSchedule(4, 1000);
        List<Integer> beats = new ArrayList<>();
        schedule.due(0, beats::add);
        assertThat(beats).containsExactly(0);
        schedule.due(499, beats::add);
        assertThat(beats).containsExactly(0, 1);
        schedule.due(999, beats::add);
        assertThat(beats).containsExactly(0, 1, 2, 3);
        schedule.due(1000, beats::add);
        assertThat(beats).containsExactly(0, 1, 2, 3, 0);

        var big = new HeartbeatSchedule(40_000, 10_000);
        var seen = new java.util.BitSet(40_000);
        big.due(10_000 - 1, seen::set);
        assertThat(big.sent()).isBetween(39_996L, 40_000L);       // 4 000 per second, nobody twice within a period
        big.due(10_000, seen::set);
        assertThat(seen.cardinality()).isEqualTo(40_000);         // and everybody once by the end of it
    }

    @Test
    void optionsParse() {
        var o = SwarmOptions.parse("--branches", "40000", "--regions", "8", "--nats", "nats://a:1, nats://b:2",
                "--peak", "3", "--speed", "60", "--duration", "15m", "--start", "10:30", "--notifications");
        assertThat(o.branches()).isEqualTo(40_000);
        assertThat(o.natsUrl(1)).isEqualTo("nats://a:1");
        assertThat(o.natsUrl(4)).isEqualTo("nats://b:2");
        assertThat(o.duration()).isEqualTo(Duration.ofMinutes(15));
        assertThat(o.startAt()).isEqualTo(LocalTime.of(10, 30));
        assertThat(o.notifications()).isTrue();
        assertThat(SwarmOptions.duration("0")).isZero();
        assertThat(SwarmOptions.duration("45")).isEqualTo(Duration.ofSeconds(45));
        assertThatThrownBy(() -> SwarmOptions.parse("--branches", "0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SwarmOptions.parse("--wat", "1")).hasMessageContaining("--wat");
    }

    /** Sunday is closed in the flow model: on a Sunday a run needs another day to have any events at all. */
    @Test
    void theVirtualDayCanBeAnotherDate() {
        var now = Instant.parse("2026-10-04T06:00:00Z");                          // a Sunday, 09:00 in Moscow

        assertThat(SwarmOptions.parse("--date", "2026-10-05", "--start", "12:00").virtualStart(now))
                .isEqualTo(Instant.parse("2026-10-05T09:00:00Z"));                 // Monday, noon in Moscow
        assertThat(SwarmOptions.parse("--start", "12:00").virtualStart(now)).isEqualTo(Instant.parse("2026-10-04T09:00:00Z"));
        assertThat(SwarmOptions.parse().virtualStart(now)).isEqualTo(now);
        assertThatThrownBy(() -> SwarmOptions.parse("--date", "05.10.2026")).isInstanceOf(IllegalArgumentException.class);
    }
}
