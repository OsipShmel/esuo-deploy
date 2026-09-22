package honey.road.node.planning;

import honey.road.node.config.JsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Current {@code planning} section from {@code branch_config}, kept parsed in memory.
 *
 * <p>The twin of {@link honey.road.node.model.ModelSource} down to the cache key, and for the same reason: the
 * key is the stored text, not {@code effective_version}. A version identifies when the configuration changed,
 * not what it contains, and nothing guarantees it only grows — restoring a database or resetting a stand moves
 * it back, and then two different sections share one number and the cache quietly serves the wrong profiles.
 * Comparing the text costs one primary-key read and cannot be wrong.
 */
@Component
public class PlanningSource {
    private static final Logger log = LoggerFactory.getLogger(PlanningSource.class);

    private final JdbcTemplate jdbc;
    private final JsonCodec json;

    // Начальное значение не может совпасть с содержимым столбца: первый вызов всегда читает.
    private volatile Snapshot snapshot = new Snapshot(null, PlanningConfig.NONE);

    public PlanningSource(JdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private record Snapshot(String raw, PlanningConfig config) {
    }

    public PlanningConfig current() {
        var raw = jdbc.queryForObject("SELECT planning::text FROM branch_config WHERE id=1", String.class);
        var current = snapshot;
        if (Objects.equals(current.raw(), raw)) {
            return current.config();
        }
        return reload(raw);
    }

    private synchronized PlanningConfig reload(String raw) {
        if (Objects.equals(snapshot.raw(), raw)) {
            return snapshot.config();
        }
        PlanningConfig config;
        try {
            config = PlanningConfig.of(raw == null ? null : json.readTree(raw));
        } catch (RuntimeException e) {
            // Испорченная секция не должна останавливать зал: планирование выключается, очередь работает.
            log.warn("planning section unusable, load planning is off: {}", e.toString());
            config = PlanningConfig.NONE;
        }
        snapshot = new Snapshot(raw, config);
        if (config.usable()) {
            log.info("planning applied: профилей {}", config.profiles().size());
        }
        return config;
    }
}
