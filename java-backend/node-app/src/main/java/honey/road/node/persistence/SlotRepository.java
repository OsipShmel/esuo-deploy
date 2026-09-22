package honey.road.node.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class SlotRepository {
    private final JdbcTemplate jdbc;
    public SlotRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID serviceId, Instant start) {
        jdbc.update("INSERT INTO appointment_slots(service_id,slot_start) VALUES (?,?) ON CONFLICT DO NOTHING",
                serviceId, Timestamp.from(start));
        jdbc.queryForObject("SELECT service_id FROM appointment_slots WHERE service_id=? AND slot_start=? FOR UPDATE",
                UUID.class, serviceId, Timestamp.from(start));
    }

    public long occupancy(UUID serviceId, Instant start) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM tickets WHERE service_id=? AND slot_start=? AND source='appointment'
                AND status NOT IN ('cancelled','no_show')
                """, Long.class, serviceId, Timestamp.from(start));
    }

    /** Appointments already taken across a whole hour, {@code [hourStart, hourStart+1h)} — the booking quota unit. */
    public long occupancyForHour(UUID serviceId, Instant hourStart) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM tickets WHERE service_id=? AND slot_start>=? AND slot_start<?
                AND source='appointment' AND status NOT IN ('cancelled','no_show')
                """, Long.class, serviceId, Timestamp.from(hourStart), Timestamp.from(hourStart.plusSeconds(3600)));
    }
}
