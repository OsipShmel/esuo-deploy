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

    static UUID none() { return NONE; }
}
