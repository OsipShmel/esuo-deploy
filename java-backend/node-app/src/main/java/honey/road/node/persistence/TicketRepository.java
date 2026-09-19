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
        return service.numberPrefix() + "-" + String.format(java.util.Locale.ROOT, "%03d", number);
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
    public void lockDevice(String branch, String device) {
        // Блокировка по устройству даёт понятную ошибку до срабатывания уникального индекса.
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
        // Метод только блокирует кандидата; назначение выполняет Queue Engine в той же транзакции.
        return jdbc.query("""
                SELECT * FROM tickets WHERE branch_id=:branch AND service_id=:service AND status='waiting'
                AND eligible_from <= :now AND (target_window_id IS NULL OR target_window_id=:window)
                ORDER BY due_at,seq LIMIT 1 FOR UPDATE SKIP LOCKED
                """, Map.of("branch",branch,"service",service,"window",window,"now",Timestamp.from(now)), MAPPER).stream().findFirst();
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
