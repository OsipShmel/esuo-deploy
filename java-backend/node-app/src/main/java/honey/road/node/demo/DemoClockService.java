package honey.road.node.demo;

import honey.road.contracts.error.ErrorCode;
import honey.road.contracts.error.EsuoException;
import honey.road.node.config.NodeProperties;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

/**
 * Demo clock state: anchors live in {@code demo_clock}, so a node restart continues from the same virtual moment.
 * Loaded before the other startup runners.
 */
@Service
@Order(-10)
public class DemoClockService implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoClockService.class);
    static final double MAX_RATE = 1000;

    private final JdbcTemplate jdbc;
    private final NodeProperties node;
    private final Clock clock;
    private final Clock realClock;

    public DemoClockService(JdbcTemplate jdbc, NodeProperties node, Clock clock, @Qualifier("realClock") Clock realClock) {
        this.jdbc = jdbc; this.node = node; this.clock = clock; this.realClock = realClock;
    }

    public record State(boolean enabled, Instant now, double rate) {
    }

    public boolean enabled() { return node.demoMode() && clock instanceof DemoClock; }

    public State state() {
        return enabled() ? new State(true, clock.instant(), ((DemoClock) clock).rate()) : new State(false, realClock.instant(), 1.0);
    }

    /** {@code now} or {@code rate} may be omitted: the missing one keeps its current value. */
    public State set(@Nullable Instant now, @Nullable Double rate) {
        if (!enabled()) {
            throw new EsuoException(ErrorCode.NOT_FOUND, "Демо-режим выключен (DEMO_MODE=false)");
        }
        if (rate != null && (rate <= 0 || rate > MAX_RATE)) {
            throw new EsuoException(ErrorCode.VALIDATION_ERROR, "rate: больше 0 и не больше " + (int) MAX_RATE);
        }
        var demo = (DemoClock) clock;
        var anchor = demo.set(now == null ? demo.instant() : now, rate == null ? demo.rate() : rate);
        jdbc.update("""
                INSERT INTO demo_clock(id, anchor_real, anchor_virtual, rate, updated_at) VALUES (1, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET anchor_real=EXCLUDED.anchor_real, anchor_virtual=EXCLUDED.anchor_virtual,
                       rate=EXCLUDED.rate, updated_at=EXCLUDED.updated_at
                """, Timestamp.from(anchor.real()), Timestamp.from(anchor.virtual()), anchor.rate(),
                Timestamp.from(realClock.instant()));
        log.info("demo clock set: now={} rate={}", anchor.virtual(), anchor.rate());
        return state();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled()) { return; }
        jdbc.query("SELECT anchor_real, anchor_virtual, rate FROM demo_clock WHERE id=1", rs -> {
            var anchor = new DemoClock.Anchor(rs.getTimestamp("anchor_real").toInstant(),
                    rs.getTimestamp("anchor_virtual").toInstant(), rs.getDouble("rate"));
            ((DemoClock) clock).anchor(anchor);
            log.info("demo clock restored: now={} rate={}", clock.instant(), anchor.rate());
        });
    }
}
