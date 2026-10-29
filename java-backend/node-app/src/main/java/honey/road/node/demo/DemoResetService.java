package honey.road.node.demo;

import honey.road.contracts.error.ErrorCode;
import honey.road.contracts.error.EsuoException;
import honey.road.node.config.NodeProperties;
import honey.road.node.planning.PlanningSource;
import honey.road.node.planning.LoadStateRepository;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * {@code POST /admin/demo/reset} (stage 6, "мелочь"): a clean slate between demo runs, not a scenario loader —
 * the trace player is {@code scripts/demo} and reads its own seed and history separately. What this clears is
 * exactly what {@code AbstractNodeIT} clears between tests, by the same technique: {@code TRUNCATE} with the
 * append-only triggers (sect 8.3) disabled for the one statement, which needs the node's DB role to be the
 * bootstrap Postgres superuser. In this stand it always is — {@code deploy/branch} never creates a
 * lower-privileged role — but that is a property of the stand, not of Postgres in general, so this stays behind
 * {@code DEMO_MODE=true} and nowhere near a real deployment.
 */
@Service
public class DemoResetService {
    private static final Logger log = LoggerFactory.getLogger(DemoResetService.class);

    private final JdbcTemplate jdbc;
    private final NodeProperties node;
    private final DemoClockService clock;
    private final PlanningSource planning;
    private final LoadStateRepository loadState;
    private final Clock realClock;

    public DemoResetService(JdbcTemplate jdbc, NodeProperties node, DemoClockService clock, PlanningSource planning,
                            LoadStateRepository loadState, @Qualifier("realClock") Clock realClock) {
        this.jdbc = jdbc; this.node = node; this.clock = clock; this.planning = planning;
        this.loadState = loadState; this.realClock = realClock;
    }

    public record Result(boolean reset, @Nullable String scenario) {
    }

    /** @param scenario day profile to select for the next run (load-planning.md 3.1), or {@code null} to leave it */
    @Transactional
    public Result reset(@Nullable String scenario) {
        if (!node.demoMode()) {
            throw new EsuoException(ErrorCode.NOT_FOUND, "Демо-режим выключен (DEMO_MODE=false)");
        }
        var code = scenario == null || scenario.isBlank() ? null : scenario.trim();
        if (code != null && !planning.current().profiles().containsKey(code)) {
            throw new EsuoException(ErrorCode.VALIDATION_ERROR,
                    "Неизвестный профиль дня; опубликованы: " + String.join(", ", planning.current().profiles().keySet()));
        }

        jdbc.execute((ConnectionCallback<Void>) c -> {
            try (var st = c.createStatement()) {
                st.execute("SET session_replication_role = replica");
                st.execute("TRUNCATE tickets, ticket_events, window_events, outbox, idempotency_keys, "
                        + "ticket_counters, incidents CASCADE");
                st.execute("SET session_replication_role = DEFAULT");
            }
            return null;
        });
        jdbc.update("UPDATE windows SET status='closed', opened_at=NULL, current_employee_id=NULL");
        jdbc.update("DELETE FROM handling_stats");     // иначе heuristic-скоринг и ETA тащат статистику прошлого прогона
        // Уровень нагрузки переживает рестарт узла по замыслу (load-planning.md 4.2) — между демо-прогонами
        // его, наоборот, надо снять, иначе следующий прогон достаётся перегрузка от предыдущего.
        jdbc.update("DELETE FROM load_state");

        var now = realClock.instant();
        if (clock.enabled()) {
            clock.set(now, 1.0);
        }
        if (code != null) {
            loadState.manualProfile(code, now.atZone(ZoneId.of(node.timezone())).toLocalDate(), now);
        }
        log.info("demo reset: scenario={}", code);
        return new Result(true, code);
    }
}
