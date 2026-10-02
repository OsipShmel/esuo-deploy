package honey.road.node.it;

import honey.road.node.config.JsonCodec;
import honey.road.node.messaging.heartbeat.HeartbeatPublisher;
import honey.road.node.messaging.inbox.BranchInboxProcessor;
import honey.road.node.queue.application.AdminCatalog;
import honey.road.node.queue.application.BranchConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Heartbeat (sect 4.1, 10.1): contract for the center registry and network overview. {@code config_version} is the
 * center-published version; {@code effective_config_version} also grows with local admin overrides.
 */
class HeartbeatIT extends AbstractNodeIT {
    @Autowired HeartbeatPublisher heartbeat;
    @Autowired BranchInboxProcessor inbox;
    @Autowired BranchConfigService config;
    @Autowired AdminCatalog catalog;
    @Autowired JsonCodec codec;

    private static int intOf(Map<String, Object> hb, String key) {
        return ((Number) hb.get(key)).intValue();
    }

    @Test
    void configVersionIsTheOneCenterPublishedAndEffectiveGrowsWithLocalChanges() throws Exception {
        var before = heartbeat.snapshot();
        assertThat(intOf(before, "config_version")).isPositive();

        inbox.process("center.config.branch.b01", "hb-1", "{\"version\":50,\"scope\":\"branch\",\"queue\":{\"slack_min\":7},"
                + "\"bounds\":{\"slack_min\":{\"min\":0,\"max\":15}}}");
        var applied = heartbeat.snapshot();
        assertThat(intOf(applied, "config_version")).isEqualTo(50);                        // center version as published
        int effective = intOf(applied, "effective_config_version");
        assertThat(effective).isGreaterThan(intOf(before, "effective_config_version"));

        config.putOverrides(JsonMapper.builder().build().readTree("{\"slack_min\":10}"));
        var local = heartbeat.snapshot();
        assertThat(intOf(local, "config_version")).isEqualTo(50);                          // a local change leaves the central version alone
        assertThat(intOf(local, "effective_config_version")).isGreaterThan(effective);
    }

    @Test
    void heartbeatCarriesTheFieldsTheCenterRelyOn() {
        var hb = heartbeat.snapshot();
        assertThat(hb).containsKeys("branch_id", "name", "timezone", "sent_at", "config_version", "effective_config_version",
                "outbox_pending", "outbox_lag_sec", "windows_total", "windows_open", "waiting", "overdue", "oldest_wait_sec", "services");
        assertThat(hb.get("branch_id")).isEqualTo("b01");
        assertThat((List<?>) hb.get("services")).hasSize(3);
    }

    @Test
    void catalogInHeartbeatFollowsAdminChangesAndHidesDisabledEntries() {
        var created = catalog.createService("ci-hb", "CI Для heartbeat", "Ч", 15, 1);
        try {
            assertThat((List<?>) heartbeat.snapshot().get("services")).hasSize(4);
            catalog.deleteService(created.id());
            assertThat((List<?>) heartbeat.snapshot().get("services")).hasSize(3);          // a disabled service is gone from the catalog
        } finally {
            jdbc.update("DELETE FROM services WHERE code = 'ci-hb'");
        }
    }

    @Test
    void queueCountersReflectTheCurrentState() {
        openWindow(W1, E1, PARCEL);
        var late = qr(PARCEL, "late");
        qr(PARCEL, "fresh");
        jdbc.update("UPDATE tickets SET due_at = now() - interval '2 minutes' WHERE id=?", late);

        var hb = heartbeat.snapshot();
        assertThat(intOf(hb, "waiting")).isEqualTo(2);
        assertThat(intOf(hb, "overdue")).isEqualTo(1);
        assertThat(intOf(hb, "windows_open")).isEqualTo(1);
        assertThat(intOf(hb, "outbox_pending")).isPositive();                                // events wait for NATS
    }

    @Test
    void closingWindowIsNotCountedAsOpen() {
        openWindow(W1, E1, PARCEL);
        qr(PARCEL, "a");
        callNext.execute(W1, null);
        windows.close(W1, "graceful", E1);                               // active ticket → window closing

        var hb = heartbeat.snapshot();
        assertThat(intOf(hb, "windows_open")).isZero();
    }

    /** Design 6.1: the server's own state for the center's metrics and rules. Here without NATS and the leaf monitor. */
    @Test
    @SuppressWarnings("unchecked")
    void heartbeatCarriesSysBlock() {
        var sys = (Map<String, Object>) heartbeat.snapshot().get("sys");

        assertThat(sys).containsKeys("sent_at_system", "started_at", "uptime_sec", "app_version", "cpu_count",
                "heap_used_mb", "db_pool_max", "db_size_mb", "disk_free_mb", "disk_total_mb", "http_requests",
                "sse_clients", "leaf_connected", "log_errors", "log_warns");
        assertThat(sys.get("leaf_connected")).isEqualTo(false);
        assertThat(sys).doesNotContainKeys("leaf_rtt_ms", "nats_reconnects");             // no source => no field
        assertThat(((Number) sys.get("db_size_mb")).longValue()).isPositive();
        assertThat(codec.writeValueAsString(sys).length()).isLessThan(1000);                 // ≈ 600 bytes per heartbeat
    }
}
