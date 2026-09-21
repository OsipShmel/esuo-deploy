package honey.road.center.centralconfig;

import honey.road.center.nats.CenterNats;
import honey.road.contracts.event.Subjects;
import honey.road.contracts.http.EsuoHeaders;
import honey.road.contracts.inbox.ConfigMessage;
import io.nats.client.Connection;
import io.nats.client.PublishOptions;
import io.nats.client.impl.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles and publishes the full branch config (sect 4.8): global ⊕ branch overrides (deep merge; branch bounds
 * replace the global ones) plus all branch staff, one message to {@code center.config.branch.{b}}.
 * {@code center.config.global} is never published (D15). One network-wide version per change.
 * <p>The DB records the publication first; the NATS publish happens after commit and, if it fails, is retried by
 * {@link ConfigReconciler}.
 */
@Service
public class ConfigPublisher {
    private static final Logger log = LoggerFactory.getLogger(ConfigPublisher.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final ConfigStore store;
    private final CenterNats nats;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;

    ConfigPublisher(ConfigStore store, CenterNats nats, TransactionTemplate tx, JsonMapper json, Clock clock) {
        this.store = store; this.nats = nats; this.tx = tx; this.json = json; this.clock = clock;
    }

    public record Published(int version, List<String> publishedTo) {
    }

    /** New version for every known branch (after a global change). */
    public Published publishAll(String author) {
        var result = tx.execute(s -> {
            int version = store.nextVersion();
            var branches = store.states().stream().map(ConfigStore.BranchState::id).toList();
            branches.forEach(b -> store.recordVersion(version, b, withoutHashes(assemble(b, version)), author, clock.instant()));
            return new Published(version, branches);
        });
        result.publishedTo().forEach(b -> send(b, result.version(), false));
        return result;
    }

    /** New version for one branch (its overrides or staff changed, or it just appeared). */
    public Published publishTo(String branchId, String author) {
        int version = tx.execute(s -> {
            int v = store.nextVersion();
            store.recordVersion(v, branchId, withoutHashes(assemble(branchId, v)), author, clock.instant());
            return v;
        });
        send(branchId, version, false);
        return new Published(version, List.of(branchId));
    }

    /**
     * Publishes the branch's current config under {@code version}. A resend uses a new {@code Nats-Msg-Id} so the
     * stream does not drop it as a duplicate; a node that already has the version just skips it.
     */
    boolean send(String branchId, int version, boolean resend) {
        var connection = nats.connection();
        if (connection.isEmpty()) {
            log.info("config v{} for {} not sent: no NATS, reconciler will retry", version, branchId);
            return false;
        }
        var now = clock.instant();
        var msgId = "cfg-" + branchId + "-" + version + (resend ? "-r" + now.getEpochSecond() : "");
        try {
            var body = json.writeValueAsBytes(assemble(branchId, version));
            publish(connection.get(), Subjects.configBranch(branchId), msgId, body);
            store.markSent(branchId, version, now);
            log.info("config v{} {} to {} ({})", version, resend ? "resent" : "published", branchId, msgId);
            return true;
        } catch (Exception e) {
            log.warn("config v{} for {} not published: {}", version, branchId, e.toString());
            return false;
        }
    }

    ConfigMessage assemble(String branchId, int version) {
        var global = store.global();
        var branch = store.branch(branchId);
        var bounds = branch.bounds() != null ? branch.bounds() : global.bounds();
        var employees = store.employees(branchId).stream()
                .map(e -> new ConfigMessage.Employee(e.id(), e.fullName(), e.active())).toList();
        var users = store.users(branchId).stream()
                .map(u -> new ConfigMessage.User(u.id(), u.login(), u.passwordHash(), u.role(), u.employeeId(), u.active()))
                .toList();
        return new ConfigMessage(version, "branch",
                ConfigMaps.merge(global.queue(), branch.queue()),
                bounds == null ? Map.of() : bounds,
                ConfigMaps.merge(global.planning(), branch.planning()),
                // Модель: глобальная — параметры сети, переопределение отделения — его собственные
                // (в том числе {"enabled": false}, чтобы выключить модель на одном отделении).
                ConfigMaps.merge(global.model(), branch.model()),
                employees, users);
    }

    /** Journal and API copy: same message, password hashes removed. */
    Map<String, Object> withoutHashes(ConfigMessage message) {
        Map<String, Object> tree = new LinkedHashMap<>(json.convertValue(message, MAP));
        if (tree.get("users") instanceof List<?> users) {
            tree.put("users", users.stream().map(u -> {
                @SuppressWarnings("unchecked")
                var copy = new LinkedHashMap<>((Map<String, Object>) u);
                copy.remove("password_hash");
                return copy;
            }).toList());
        }
        return tree;
    }

    private static void publish(Connection c, String subject, String msgId, byte[] body) throws Exception {
        var headers = new Headers().add(EsuoHeaders.CONTENT_TYPE, "application/json");
        c.jetStream().publish(subject, headers, body,
                PublishOptions.builder().messageId(msgId).streamTimeout(Duration.ofSeconds(5)).build());
    }
}
