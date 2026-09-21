package honey.road.node.model;

import honey.road.node.config.JsonCodec;
import honey.road.node.config.NodeProperties;
import honey.road.node.persistence.CatalogRepository;
import honey.road.node.queue.domain.PostalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Current model coefficients from {@code branch_config.model}, kept in memory.
 *
 * <p>Parsed only when the section actually changes, because {@link #current()} is called for every distinct
 * service-employee-category combination inside one ETA simulation: parsing on each call would turn a single
 * estimate into a pile of JSON parses. What guards the cache is the stored text itself — see {@link #current()}
 * for why the configuration version cannot serve as that key.
 *
 * <p>The section keys services by {@code services.code} rather than by id on purpose: service ids belong to the
 * branch, while the center knows only codes (it learns them from the heartbeat), so the same coefficients can be
 * published to the whole network. The code map is resolved here and refreshed when a lookup misses, which covers
 * a service added through the admin CRUD without tying the cache to the config version.
 */
@Component
public class ModelSource {
    private static final Logger log = LoggerFactory.getLogger(ModelSource.class);

    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final CatalogRepository catalog;
    private final NodeProperties node;

    // Начальное значение не может совпасть с содержимым столбца: первый вызов всегда читает.
    private volatile Snapshot snapshot = new Snapshot(null, ModelParams.NONE);
    private volatile Map<UUID, String> serviceCodes = Map.of();

    public ModelSource(JdbcTemplate jdbc, JsonCodec json, CatalogRepository catalog, NodeProperties node) {
        this.jdbc = jdbc;
        this.json = json;
        this.catalog = catalog;
        this.node = node;
    }

    private record Snapshot(String raw, ModelParams params) {
    }

    /**
     * The cache key is the stored section itself, not {@code effective_version}.
     *
     * <p>The version looks like the obvious key and is the wrong one: it identifies when the configuration changed,
     * not what it contains. Nothing guarantees it only ever grows — restoring a database or resetting a stand
     * moves it back, and then two different sections share one number and the cache serves the wrong model with
     * no error anywhere. Comparing the raw text costs one small row read and cannot be wrong; the expensive part,
     * parsing, still happens only when the section actually changes.
     */
    public ModelParams current() {
        var raw = jdbc.queryForObject("SELECT model::text FROM branch_config WHERE id=1", String.class);
        var current = snapshot;
        if (Objects.equals(current.raw(), raw)) {
            return current.params();
        }
        return reload(raw);
    }

    private synchronized ModelParams reload(String raw) {
        if (Objects.equals(snapshot.raw(), raw)) {
            return snapshot.params();
        }
        ModelParams params;
        try {
            params = ModelParams.of(raw == null ? null : json.readTree(raw));
        } catch (RuntimeException e) {
            // Испорченная секция не должна останавливать зал: уходим на каскад и говорим об этом громко.
            log.warn("model section unusable, falling back to handling_stats: {}", e.toString());
            params = ModelParams.NONE;
        }
        snapshot = new Snapshot(raw, params);
        if (params.usable()) {
            log.info("duration model {} applied", params.version());
        }
        return params;
    }

    /** Service code for the model's keys; {@code null} for an unknown service — then the chain falls back. */
    public String serviceCode(UUID serviceId) {
        if (serviceId == null) {
            return null;
        }
        var codes = serviceCodes;
        var code = codes.get(serviceId);
        if (code != null) {
            return code;
        }
        return refreshServiceCodes().get(serviceId);
    }

    private synchronized Map<UUID, String> refreshServiceCodes() {
        var codes = catalog.services(node.branchId()).stream()
                .collect(Collectors.toMap(PostalService::id, PostalService::code, (a, b) -> a, HashMap::new));
        serviceCodes = Map.copyOf(codes);
        return serviceCodes;
    }
}
