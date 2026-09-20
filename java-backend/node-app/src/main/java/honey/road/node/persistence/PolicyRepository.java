package honey.road.node.persistence;

import honey.road.node.config.JsonCodec;
import honey.road.node.queue.application.ConfigRules;
import honey.road.node.queue.domain.QueuePolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;

/**
 * Effective queue rules = central version + local admin overrides (sect 4.8). Its {@code config_version} is
 * {@code effective_version}: grows on any change and is stamped into every new ticket.
 */
@Repository
public class PolicyRepository {
    private final JdbcTemplate jdbc;
    private final JsonCodec json;

    public PolicyRepository(JdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public QueuePolicy current() {
        return jdbc.queryForObject("""
                SELECT central_config::text AS c, local_overrides::text AS o, effective_version AS v
                  FROM branch_config WHERE id=1
                """, (rs, n) -> compose(json.readTree(rs.getString("c")), json.readTree(rs.getString("o")), rs.getInt("v")));
    }

    /** Branch config versions: central (published by the center) and effective (with local overrides). */
    public record Versions(int central, int effective) {
    }

    public Versions versions() {
        return jdbc.queryForObject("SELECT central_version, effective_version FROM branch_config WHERE id=1",
                (rs, n) -> new Versions(rs.getInt("central_version"), rs.getInt("effective_version")));
    }

    /** Builds the policy from central config and overrides; throws on an invalid combination. */
    public QueuePolicy compose(JsonNode central, JsonNode overrides, int effectiveVersion) {
        var merged = ConfigRules.merge(central, overrides);
        merged.put("config_version", effectiveVersion);
        return json.treeToValue(merged, QueuePolicy.class);
    }
}
