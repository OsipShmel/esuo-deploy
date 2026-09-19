package honey.road.node.it;

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
 * Heartbeat (раздел 4.1, 10.1) — контракт для реестра и обзора сети в центре. {@code config_version} — версия, опубликованная
 * центром; {@code effective_config_version} — действующая (растёт и от локальных изменений руководителя).
 */
class HeartbeatIT extends AbstractNodeIT {
    @Autowired HeartbeatPublisher heartbeat;
    @Autowired BranchInboxProcessor inbox;
    @Autowired BranchConfigService config;
    @Autowired AdminCatalog catalog;

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
        assertThat(intOf(applied, "config_version")).isEqualTo(50);                        // версия центра, как он её опубликовал
        int effective = intOf(applied, "effective_config_version");
        assertThat(effective).isGreaterThan(intOf(before, "effective_config_version"));

        config.putOverrides(JsonMapper.builder().build().readTree("{\"slack_min\":10}"));
        var local = heartbeat.snapshot();
        assertThat(intOf(local, "config_version")).isEqualTo(50);                          // локальное изменение центральную версию не трогает
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
            assertThat((List<?>) heartbeat.snapshot().get("services")).hasSize(3);          // отключённая услуга из каталога ушла
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
        assertThat(intOf(hb, "outbox_pending")).isPositive();                                // события ждут связи с NATS
    }
}
