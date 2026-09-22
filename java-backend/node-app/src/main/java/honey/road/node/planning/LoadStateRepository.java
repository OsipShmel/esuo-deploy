package honey.road.node.planning;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The single {@code load_state} row: level with its hysteresis timers, and the manually chosen day profile.
 *
 * <p>Reads are frequent (every screen refresh) and writes are rare — the level changes a handful of times a day —
 * so the row is read plainly and written only when something actually changed.
 */
@Repository
public class LoadStateRepository {

    private final JdbcTemplate jdbc;

    public LoadStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param manualProfile profile code chosen by the branch admin, or {@code null}
     * @param manualDay     the day that choice was made for; it expires with the day (sect 3.1)
     */
    public record Row(LoadLevels.State state, String manualProfile, LocalDate manualDay) {
    }

    public Row load(Instant now) {
        var row = jdbc.query("""
                SELECT level, level_since, up_since, down_since, manual_profile, manual_profile_day
                  FROM load_state WHERE id = 1
                """, (rs, n) -> new Row(
                new LoadLevels.State(rs.getString("level"), instant(rs.getTimestamp("level_since")),
                        instant(rs.getTimestamp("up_since")), instant(rs.getTimestamp("down_since"))),
                rs.getString("manual_profile"), rs.getObject("manual_profile_day", LocalDate.class)));
        return row.isEmpty() ? new Row(LoadLevels.State.initial(now), null, null) : row.getFirst();
    }

    /** Writes the level and its timers; the manual profile is not touched here. */
    @Transactional
    public void save(LoadLevels.State state, Instant now) {
        int updated = jdbc.update("""
                UPDATE load_state SET level=?, level_since=?, up_since=?, down_since=?, updated_at=? WHERE id=1
                """, text(state.level()), timestamp(state.since()), timestamp(state.upSince()),
                timestamp(state.downSince()), timestamp(now));
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO load_state(id, level, level_since, up_since, down_since, updated_at)
                    VALUES (1, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING
                    """, text(state.level()), timestamp(state.since()), timestamp(state.upSince()),
                    timestamp(state.downSince()), timestamp(now));
        }
    }

    /**
     * Sets or clears the manual day profile. {@code profile == null} returns the branch to the center's calendar.
     *
     * <p>The row may not exist yet — planning starts working before the level has ever been written — so this
     * inserts a default {@code normal} level rather than failing on a missing row.
     */
    @Transactional
    public void manualProfile(String profile, LocalDate day, Instant now) {
        int updated = jdbc.update("""
                UPDATE load_state SET manual_profile=?, manual_profile_day=?, updated_at=? WHERE id=1
                """, text(profile), date(profile == null ? null : day), timestamp(now));
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO load_state(id, level, level_since, manual_profile, manual_profile_day, updated_at)
                    VALUES (1, ?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE
                       SET manual_profile=EXCLUDED.manual_profile, manual_profile_day=EXCLUDED.manual_profile_day,
                           updated_at=EXCLUDED.updated_at
                    """, text(LoadLevel.NORMAL), timestamp(now), text(profile),
                    date(profile == null ? null : day), timestamp(now));
        }
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    // Типы параметров задаются явно: половина полей здесь необязательна, а драйвер не обязан угадывать
    // тип по голому null — «переход не идёт» это нормальное состояние, а не исключительная ситуация.
    private static SqlParameterValue timestamp(Instant value) {
        return new SqlParameterValue(Types.TIMESTAMP, value == null ? null : Timestamp.from(value));
    }

    private static SqlParameterValue date(LocalDate value) {
        return new SqlParameterValue(Types.DATE, value == null ? null : Date.valueOf(value));
    }

    private static SqlParameterValue text(String value) {
        return new SqlParameterValue(Types.VARCHAR, value);
    }
}
