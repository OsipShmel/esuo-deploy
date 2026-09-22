package honey.road.node.persistence;

import honey.road.node.queue.domain.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import honey.road.node.queue.domain.*;
import honey.road.node.queue.domain.DomainException;
import honey.road.node.queue.domain.PostalService;
import honey.road.node.queue.domain.Ticket;
import honey.road.node.queue.domain.TicketSource;
import honey.road.node.queue.domain.TicketStatus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Repository
public class TicketRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public TicketRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        var timestamp = rs.getTimestamp(name);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static final RowMapper<Ticket> MAPPER = (rs, n) -> new Ticket(
            rs.getObject("id", UUID.class), rs.getObject("public_token", UUID.class), rs.getString("branch_id"),
            rs.getObject("service_id", UUID.class), TicketSource.valueOf(rs.getString("source")),
            TicketStatus.valueOf(rs.getString("status")), rs.getString("client_category"), rs.getInt("config_version"),
            rs.getLong("seq"), rs.getString("display_number"), instant(rs,"issued_at"), rs.getObject("issued_day", LocalDate.class),
            instant(rs,"slot_start"), instant(rs,"slot_end"), instant(rs,"eligible_from"), instant(rs,"due_at"),
            rs.getObject("window_id", UUID.class), rs.getObject("employee_id", UUID.class), rs.getObject("target_window_id", UUID.class),
            rs.getString("device_id"), rs.getString("mp_appointment_id"), instant(rs,"updated_at"), rs.getInt("version"), instant(rs,"called_at"),
            rs.getInt("call_count"), instant(rs,"checked_in_at"), instant(rs,"serving_started_at"), instant(rs,"completed_at"),
            rs.getInt("postpone_count"));

    @Transactional(propagation = Propagation.MANDATORY)
    public String nextNumber(String branch, PostalService service, LocalDate day) {
        Long number = jdbc.queryForObject("""
                INSERT INTO ticket_counters(branch_id,service_id,day,last_value) VALUES (:branch,:service,:day,1)
                ON CONFLICT (branch_id,service_id,day) DO UPDATE SET last_value=ticket_counters.last_value+1
                RETURNING last_value
                """, Map.of("branch", branch, "service", service.id(), "day", day), Long.class);
        return service.numberPrefix() + "-" + String.format(Locale.ROOT, "%03d", number);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Ticket insert(Ticket t) {
        var p = new MapSqlParameterSource().addValue("id",t.id()).addValue("token",t.publicToken())
                .addValue("branch",t.branchId()).addValue("service",t.serviceId()).addValue("source",t.source().name())
                .addValue("status",t.status().name()).addValue("category",t.clientCategory()).addValue("config",t.configVersion())
                .addValue("number",t.displayNumber()).addValue("issued",Timestamp.from(t.issuedAt())).addValue("day",t.issuedDay())
                .addValue("slotStart",timestamp(t.slotStart())).addValue("slotEnd",timestamp(t.slotEnd()))
                .addValue("eligible",timestamp(t.eligibleFrom())).addValue("due",timestamp(t.dueAt()))
                .addValue("device",t.deviceId()).addValue("mp",t.mpAppointmentId())
                .addValue("checkedIn",timestamp(t.checkedInAt()));
        return jdbc.queryForObject("""
                INSERT INTO tickets(id,public_token,branch_id,service_id,source,status,client_category,config_version,
                display_number,issued_at,issued_day,slot_start,slot_end,eligible_from,due_at,device_id,mp_appointment_id,updated_at,checked_in_at)
                VALUES (:id,:token,:branch,:service,:source,:status,:category,:config,:number,:issued,:day,
                :slotStart,:slotEnd,:eligible,:due,:device,:mp,:issued,:checkedIn) RETURNING *
                """, p, MAPPER);
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    public Ticket byToken(String branch, UUID token, boolean lock) {
        return jdbc.query("SELECT * FROM tickets WHERE branch_id=:branch AND public_token=:token" + (lock ? " FOR UPDATE" : ""),
                Map.of("branch",branch,"token",token), MAPPER).stream().findFirst()
                .orElseThrow(() -> new DomainException("TICKET_NOT_FOUND", "Талон не найден"));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    /** MP booking ticket by external id (unique). */
    public Ticket byMpAppointmentId(String branch, String mpAppointmentId) {
        return jdbc.query("SELECT * FROM tickets WHERE branch_id=:branch AND mp_appointment_id=:id",
                Map.of("branch",branch,"id",mpAppointmentId), MAPPER).stream().findFirst()
                .orElseThrow(() -> new DomainException("TICKET_NOT_FOUND", "Запись МП не найдена"));
    }

    public void lockDevice(String branch, String device) {
        jdbc.getJdbcTemplate().query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> { }, "device:" + branch + ":" + device);
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM tickets WHERE branch_id=:branch AND device_id=:device
                AND status IN ('booked','waiting','called','serving'))
                """, Map.of("branch",branch,"device",device), Boolean.class))) {
            throw new DomainException("ACTIVE_TICKET_EXISTS", "У устройства уже есть активный талон");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Ticket> lockHead(String branch, UUID service, UUID window, Instant now) {
        // Only locks the candidate; the Queue Engine assigns it in the same transaction.
        return jdbc.query("""
                SELECT * FROM tickets WHERE branch_id=:branch AND service_id=:service AND status='waiting'
                AND eligible_from <= :now AND (target_window_id IS NULL OR target_window_id=:window)
                ORDER BY due_at,seq LIMIT 1 FOR UPDATE SKIP LOCKED
                """, Map.of("branch",branch,"service",service,"window",window,"now",Timestamp.from(now)), MAPPER).stream().findFirst();
    }

    public Ticket byId(String branch, UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM tickets WHERE branch_id=:branch AND id=:id" + (lock ? " FOR UPDATE" : ""),
                Map.of("branch",branch,"id",id), MAPPER).stream().findFirst()
                .orElseThrow(() -> new DomainException("TICKET_NOT_FOUND", "Талон не найден"));
    }

    /** Active (called/serving) ticket of the window; at most one, enforced by ux_one_active_ticket_per_window. */
    public Optional<Ticket> activeForWindow(UUID window, boolean lock) {
        return jdbc.query("SELECT * FROM tickets WHERE window_id=:w AND status IN ('called','serving')" + (lock ? " FOR UPDATE" : ""),
                Map.of("w", window), MAPPER).stream().findFirst();
    }

    /**
     * Applies a change with an optimistic version check. The row is already locked by the command, so a mismatch
     * only means a stale client {@code expected_version}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Ticket apply(TicketChange c, Instant now) {
        var p = new MapSqlParameterSource()
                .addValue("id", c.base().id()).addValue("version", c.base().version())
                .addValue("status", c.status().name()).addValue("service", c.serviceId())
                .addValue("window", c.windowId()).addValue("employee", c.employeeId())
                .addValue("target", c.targetWindowId())
                .addValue("eligible", timestamp(c.eligibleFrom())).addValue("due", timestamp(c.dueAt()))
                .addValue("called", timestamp(c.calledAt())).addValue("checkedIn", timestamp(c.checkedInAt()))
                .addValue("serving", timestamp(c.servingStartedAt())).addValue("completed", timestamp(c.completedAt()))
                .addValue("callCount", c.callCount()).addValue("postponeCount", c.postponeCount())
                .addValue("reason", c.closeReason()).addValue("now", Timestamp.from(now));
        return jdbc.query("""
                UPDATE tickets SET status=:status, service_id=:service, window_id=:window, employee_id=:employee,
                       target_window_id=:target, eligible_from=:eligible, due_at=:due, called_at=:called,
                       checked_in_at=:checkedIn, serving_started_at=:serving, completed_at=:completed,
                       call_count=:callCount, postpone_count=:postponeCount,
                       close_reason=COALESCE(:reason, close_reason), updated_at=:now, version=version+1
                 WHERE id=:id AND version=:version RETURNING *
                """, p, MAPPER).stream().findFirst()
                .orElseThrow(() -> new DomainException("STALE_STATE", "Талон изменился, обновите данные"));
    }

    /** Waiting tickets of the same service ahead of this one in EDF order. */
    public int aheadOf(Ticket t) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM tickets WHERE branch_id=:b AND service_id=:s AND status='waiting'
                   AND (due_at, seq) < (:due, :seq)
                """, Map.of("b", t.branchId(), "s", t.serviceId(), "due", Timestamp.from(t.dueAt()), "seq", t.seq()), Integer.class);
    }

    /** Waiting tickets not yet notified about the approaching turn (sect 4.7). */
    public Set<UUID> waitingNotNotified(String branch) {
        return new java.util.HashSet<>(jdbc.queryForList("""
                SELECT id FROM tickets WHERE branch_id=:b AND status='waiting' AND notified_approaching_at IS NULL
                """, Map.of("b", branch), UUID.class));
    }

    /**
     * Sets the "approaching notified" flag. {@code IS NULL} makes it check-and-set: of two racing passes only one wins,
     * so the event is emitted exactly once.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markApproachingNotified(UUID id, Instant now) {
        return jdbc.update("""
                UPDATE tickets SET notified_approaching_at=:now
                 WHERE id=:id AND status='waiting' AND notified_approaching_at IS NULL
                """, Map.of("id", id, "now", Timestamp.from(now))) == 1;
    }

    /** Waiting tickets targeted at the window (S4: the target is reset when the window closes). */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Ticket> lockTargetedTo(UUID window) {
        return jdbc.query("SELECT * FROM tickets WHERE target_window_id=:w AND status='waiting' ORDER BY seq FOR UPDATE",
                Map.of("w", window), MAPPER);
    }

    /** Branch queue in EDF order (operator, board, ETA). */
    public List<Ticket> waiting(String branch) {
        return jdbc.query("SELECT * FROM tickets WHERE branch_id=:b AND status='waiting' ORDER BY due_at, seq",
                Map.of("b", branch), MAPPER);
    }

    /** Tickets in the given (active) statuses. */
    public List<Ticket> byStatuses(String branch, Collection<String> statuses) {
        return jdbc.query("SELECT * FROM tickets WHERE branch_id=:b AND status IN (:st) ORDER BY seq",
                new MapSqlParameterSource().addValue("b", branch).addValue("st", statuses), MAPPER);
    }

    /**
     * Appointments for a period, for the hourly forecast (load-planning.md 4.1).
     *
     * <p>Cancelled and no-show bookings are excluded: they bring no work and their slot is free again.
     * A booking whose client already checked in is still counted — the work is ahead either way, and the
     * status it moved to says nothing about the hour it was booked for.
     */
    public List<Ticket> bookedBetween(String branch, Instant from, Instant to) {
        return jdbc.query("""
                SELECT * FROM tickets WHERE branch_id=:b AND source='appointment'
                   AND slot_start >= :from AND slot_start < :to
                   AND status NOT IN ('cancelled','no_show')
                 ORDER BY slot_start
                """, Map.of("b", branch, "from", Timestamp.from(from), "to", Timestamp.from(to)), MAPPER);
    }

    /** Tickets issued in a period: the actual inflow, against which the profile forecast is judged (sect 4.3). */
    public List<Ticket> issuedBetween(String branch, Instant from, Instant to) {
        return jdbc.query("""
                SELECT * FROM tickets WHERE branch_id=:b AND issued_at >= :from AND issued_at < :to
                 ORDER BY issued_at
                """, Map.of("b", branch, "from", Timestamp.from(from), "to", Timestamp.from(to)), MAPPER);
    }

    /** Services completed in a period, for comparing measured duration with the forecast (sect 4.3). */
    public List<Ticket> completedBetween(String branch, Instant from, Instant to) {
        return jdbc.query("""
                SELECT * FROM tickets WHERE branch_id=:b AND status='done'
                   AND completed_at >= :from AND completed_at < :to AND serving_started_at IS NOT NULL
                 ORDER BY completed_at
                """, Map.of("b", branch, "from", Timestamp.from(from), "to", Timestamp.from(to)), MAPPER);
    }

    /** Latest calls (board). */
    public List<Ticket> recentCalls(String branch, int limit) {
        return jdbc.query("""
                SELECT * FROM tickets WHERE branch_id=:b AND called_at IS NOT NULL AND status IN ('called','serving')
                 ORDER BY called_at DESC LIMIT :limit
                """, Map.of("b", branch, "limit", limit), MAPPER);
    }

    /** Called longer than the timeout, for the recall scheduler (S5). */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Ticket> lockCallTimedOut(String branch, Instant threshold, int limit) {
        return jdbc.query("""
                SELECT * FROM tickets WHERE branch_id=:b AND status='called' AND called_at < :t
                 ORDER BY called_at LIMIT :limit FOR UPDATE SKIP LOCKED
                """, Map.of("b", branch, "t", Timestamp.from(threshold), "limit", limit), MAPPER);
    }

    /** Bookings without check-in past the threshold, for the expiry scheduler (S8). */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Ticket> lockBookedBefore(String branch, Instant threshold, int limit) {
        return jdbc.query("""
                SELECT * FROM tickets WHERE branch_id=:b AND status='booked' AND slot_start < :t
                 ORDER BY slot_start LIMIT :limit FOR UPDATE SKIP LOCKED
                """, Map.of("b", branch, "t", Timestamp.from(threshold), "limit", limit), MAPPER);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Ticket cancel(Ticket ticket, Instant now) {
        return jdbc.queryForObject("""
                UPDATE tickets SET status='cancelled',window_id=NULL,employee_id=NULL,target_window_id=NULL,
                close_reason='client_cancelled',updated_at=:now,version=version+1
                WHERE id=:id AND version=:version AND status IN ('booked','waiting') RETURNING *
                """, Map.of("now",Timestamp.from(now),"id",ticket.id(),"version",ticket.version()), MAPPER);
    }
}
