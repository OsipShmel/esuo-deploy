package honey.road.node.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** {@code handling_stats}: скользящее среднее длительности обслуживания (раздел 16.1). */
@Repository
public class HandlingStatsRepository {
    private static final UUID NONE = new UUID(0, 0);
    private final JdbcClient jdbc;

    public HandlingStatsRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Учёт завершённого обслуживания: агрегат по услуге и по сотруднику+услуге. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID service, UUID employee, String category, double seconds, Instant now) {
        if (seconds <= 0) { return; }
        upsert(service, null, category, seconds, now);
        if (employee != null) { upsert(service, employee, category, seconds, now); }
    }

    private void upsert(UUID service, UUID employee, String category, double seconds, Instant now) {
        jdbc.sql("""
                        INSERT INTO handling_stats(service_id,employee_id,window_id,client_category,avg_duration_sec,sample_count,updated_at)
                        VALUES (:s,:e,NULL,:c,:v,1,:now)
                        ON CONFLICT (service_id, COALESCE(employee_id, '00000000-0000-0000-0000-000000000000'),
                                     COALESCE(window_id, '00000000-0000-0000-0000-000000000000'), client_category)
                        DO UPDATE SET avg_duration_sec = (handling_stats.avg_duration_sec * handling_stats.sample_count + EXCLUDED.avg_duration_sec)
                                                         / (handling_stats.sample_count + 1),
                                      sample_count = handling_stats.sample_count + 1,
                                      updated_at = EXCLUDED.updated_at
                        """)
                .param("s", service).param("e", employee).param("c", category).param("v", seconds)
                .param("now", Timestamp.from(now)).update();
    }

    /** Средняя длительность по услуге (все сотрудники и категории), если данных достаточно. */
    public Optional<Double> averageForService(UUID service, int minSamples) {
        return jdbc.sql("""
                        SELECT sum(avg_duration_sec * sample_count) / NULLIF(sum(sample_count), 0) AS avg, sum(sample_count) AS n
                          FROM handling_stats WHERE service_id=:s AND employee_id IS NULL AND window_id IS NULL
                        """)
                .param("s", service)
                .query((rs, i) -> rs.getLong("n") >= minSamples ? Optional.of(rs.getDouble("avg")) : Optional.<Double>empty())
                .single();
    }

    /** Прогноз длительности и уровень каскада, на котором он получен (раздел 16.2). */
    public record Forecast(double seconds, String level, int samples) {
    }

    public static final String LEVEL_EMPLOYEE_SERVICE_CATEGORY = "employee_service_category";
    public static final String LEVEL_EMPLOYEE_SERVICE = "employee_service";
    public static final String LEVEL_SERVICE_CATEGORY = "service_category";
    public static final String LEVEL_SERVICE = "service";
    public static final String LEVEL_FALLBACK = "fallback";

    /**
     * Каскад уточнений: на каждом уровне значение берётся, если {@code sample_count >= minSamples}.
     * 1) сотрудник+услуга+категория, 2) сотрудник+услуга, 3) услуга+категория (все сотрудники), 4) услуга.
     * Пусто — ни на одном уровне данных достаточно нет, вызывающий берёт {@code fallback_duration_sec}.
     */
    public Optional<Forecast> forecast(UUID service, UUID employee, String category, int minSamples) {
        if (employee != null) {
            var f = level(LEVEL_EMPLOYEE_SERVICE_CATEGORY, minSamples,
                    "service_id=:s AND employee_id=:e AND window_id IS NULL AND client_category=:c", service, employee, category);
            if (f.isPresent()) { return f; }
            f = level(LEVEL_EMPLOYEE_SERVICE, minSamples,
                    "service_id=:s AND employee_id=:e AND window_id IS NULL", service, employee, category);
            if (f.isPresent()) { return f; }
        }
        var f = level(LEVEL_SERVICE_CATEGORY, minSamples,
                "service_id=:s AND employee_id IS NULL AND window_id IS NULL AND client_category=:c", service, employee, category);
        if (f.isPresent()) { return f; }
        return level(LEVEL_SERVICE, minSamples, "service_id=:s AND employee_id IS NULL AND window_id IS NULL",
                service, employee, category);
    }

    private Optional<Forecast> level(String name, int minSamples, String where, UUID service, UUID employee, String category) {
        var q = jdbc.sql("SELECT sum(avg_duration_sec * sample_count) / NULLIF(sum(sample_count), 0) AS avg, "
                        + "COALESCE(sum(sample_count), 0) AS n FROM handling_stats WHERE " + where)
                .param("s", service);
        if (where.contains(":e")) { q = q.param("e", employee); }
        if (where.contains(":c")) { q = q.param("c", category); }
        return q.query((rs, i) -> rs.getLong("n") >= Math.max(1, minSamples)
                        ? Optional.of(new Forecast(rs.getDouble("avg"), name, (int) rs.getLong("n"))) : Optional.<Forecast>empty())
                .single();
    }

    static UUID none() { return NONE; }
}
