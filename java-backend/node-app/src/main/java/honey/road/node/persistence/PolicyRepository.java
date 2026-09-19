package honey.road.node.persistence;

import honey.road.node.queue.domain.QueuePolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import honey.road.node.config.JsonCodec;

@Repository
public class PolicyRepository {
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    public PolicyRepository(JdbcTemplate jdbc, JsonCodec json) { this.jdbc = jdbc; this.json = json; }

    public QueuePolicy current() {
        // Центральные переопределения будут применяться отдельной командой Дня 4.
        return json.readValue(jdbc.queryForObject("SELECT central_config::text FROM branch_config WHERE id=1", String.class), QueuePolicy.class);
    }
}
