package honey.road.center.centralconfig;

import honey.road.center.config.CenterProperties;
import honey.road.center.network.NetworkSnapshot;
import honey.road.contracts.error.ErrorCode;
import honey.road.contracts.error.EsuoException;
import honey.road.contracts.error.FieldViolation;
import honey.road.contracts.inbox.ConfigMessage;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Service manager use cases (sect 9.5): global config, branch overrides, branch users. Every change publishes a version. */
@Service
public class ConfigService {
    private static final String LOGIN = "[a-zA-Z0-9._-]{3,64}";

    private final ConfigStore store;
    private final ConfigPublisher publisher;
    private final NetworkSnapshot network;
    private final Duration offlineAfter;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final PasswordEncoder passwords = new BCryptPasswordEncoder();

    ConfigService(ConfigStore store, ConfigPublisher publisher, NetworkSnapshot network, CenterProperties props,
                  TransactionTemplate tx, Clock clock) {
        this.store = store; this.publisher = publisher; this.network = network; this.offlineAfter = props.offlineAfter();
        this.tx = tx; this.clock = clock;
    }

    // ------------------------------------------------------------------ views

    public record SectionsView(Map<String, Object> queue, Map<String, Object> bounds, Map<String, Object> planning,
                               Map<String, Object> model, @Nullable Instant updatedAt, @Nullable String updatedBy) {
    }

    public record Overrides(@Nullable Map<String, Object> queue, @Nullable Map<String, Object> bounds,
                            @Nullable Map<String, Object> planning, @Nullable Map<String, Object> model) {
    }

    public record BranchConfigStatus(String branchId, boolean online, Overrides overrides, @Nullable Integer publishedVersion,
                                     @Nullable Integer appliedVersion, @Nullable Instant appliedAt, boolean pending) {
    }

    /** The global sections and one page of branches (scale design 8.2): 250 000 of them never come at once. */
    public record ConfigOverview(SectionsView global, int total, int offset, int limit, List<BranchConfigStatus> branches) {
    }

    public record BranchConfigView(Overrides overrides, @Nullable Map<String, Object> effective,
                                   @Nullable Integer publishedVersion, @Nullable Integer appliedVersion,
                                   @Nullable Instant appliedAt) {
    }

    public record UserView(UUID id, String login, String role, @Nullable UUID employeeId, @Nullable String fullName,
                           boolean active) {
    }

    public record UserChange(UserView user, int configVersion) {
    }

    public record VersionView(int version, String branchId, Map<String, Object> message, @Nullable String author,
                              Instant createdAt) {
    }

    /**
     * The global sections and a page of branches by a search and a region ({@code events_via} or {@code own}), found in
     * the shared picture of the network; their states and overrides — one query each. {@code limit} 1…200.
     */
    public ConfigOverview overview(@Nullable String q, @Nullable String region, int offset, int limit) {
        var g = store.global();
        var global = new SectionsView(g.queue(), g.bounds(), g.planning(), g.model(), g.updatedAt(), g.updatedBy());
        var matched = network.current().select(q, null, region, null);
        int size = Math.clamp(limit, 1, 200);
        int from = Math.clamp(offset, 0, matched.size());
        var ids = matched.subList(from, Math.min(matched.size(), from + size)).stream().map(NetworkSnapshot.Entry::id).toList();
        var states = new HashMap<String, ConfigStore.BranchState>();
        store.states(ids).forEach(s -> states.put(s.id(), s));
        var overrides = store.branches(ids);
        var now = clock.instant();
        var branches = ids.stream().filter(states::containsKey)
                .map(id -> status(states.get(id), overrides.getOrDefault(id, ConfigStore.Sections.empty()), now)).toList();
        return new ConfigOverview(global, matched.size(), from, size, branches);
    }

    public BranchConfigView branch(String branchId) {
        requireBranch(branchId);
        var s = store.state(branchId).orElseThrow();
        var overrides = overrides(store.branch(branchId));
        var effective = s.published() == null ? null : publisher.withoutHashes(publisher.assemble(branchId, s.published()));
        return new BranchConfigView(overrides, effective, s.published(), applied(s), s.appliedAt());
    }

    public List<VersionView> versions(@Nullable String branchId, int limit) {
        return store.versions(branchId, Math.clamp(limit, 1, 500)).stream()
                .map(v -> new VersionView(v.version(), v.branchId(), v.message(), v.author(), v.createdAt())).toList();
    }

    // ------------------------------------------------------------------ changes

    /** {@code {queue?, bounds?, planning?}}: an absent section is unchanged, {@code null} clears it. */
    public ConfigPublisher.Published putGlobal(Map<String, Object> patch, String author) {
        ConfigValidator.validate(patch);
        tx.executeWithoutResult(t -> store.saveGlobal(apply(store.global(), patch), author, clock.instant()));
        return publisher.publishAll(author);
    }

    /** Same shape; a cleared section falls back to the global one. */
    public ConfigPublisher.Published putBranch(String branchId, Map<String, Object> patch, String author) {
        requireBranch(branchId);
        ConfigValidator.validate(patch);
        tx.executeWithoutResult(t -> store.saveBranch(branchId, apply(store.branch(branchId), patch), author, clock.instant()));
        return publisher.publishTo(branchId, author);
    }

    public List<UserView> users(String branchId) {
        requireBranch(branchId);
        return store.users(branchId).stream().map(ConfigService::view).toList();
    }

    public UserChange createUser(String branchId, @Nullable String login, @Nullable String password, @Nullable String role,
                                 @Nullable String fullName, String author) {
        requireBranch(branchId);
        var errors = new ArrayList<FieldViolation>();
        if (login == null || !login.matches(LOGIN)) { errors.add(new FieldViolation("login", "3–64 символа: латиница, цифры, . _ -")); }
        checkPassword(password, errors);
        checkRole(role, errors);
        if (fullName == null || fullName.isBlank()) { errors.add(new FieldViolation("full_name", "Обязательно")); }
        if (!errors.isEmpty()) { throw new EsuoException(ErrorCode.VALIDATION_ERROR, "Проверьте поля", errors); }

        var userId = UUID.randomUUID();
        tx.executeWithoutResult(t -> {
            if (store.loginTaken(branchId, login)) {
                throw new EsuoException(ErrorCode.LOGIN_TAKEN, "Логин " + login + " уже есть в отделении " + branchId);
            }
            var now = clock.instant();
            var employeeId = UUID.randomUUID();
            store.insertEmployee(employeeId, branchId, fullName.strip(), now);
            store.insertUser(userId, branchId, login, passwords.encode(password), role, employeeId, now);
        });
        var published = publisher.publishTo(branchId, author);
        return new UserChange(view(store.user(branchId, userId).orElseThrow()), published.version());
    }

    public UserChange updateUser(String branchId, UUID userId, @Nullable String password, @Nullable String role,
                                 @Nullable Boolean active, String author) {
        requireBranch(branchId);
        var errors = new ArrayList<FieldViolation>();
        if (password != null) { checkPassword(password, errors); }
        if (role != null) { checkRole(role, errors); }
        if (!errors.isEmpty()) { throw new EsuoException(ErrorCode.VALIDATION_ERROR, "Проверьте поля", errors); }
        tx.executeWithoutResult(t -> {
            store.user(branchId, userId).orElseThrow(() -> new EsuoException(ErrorCode.NOT_FOUND, "Пользователь не найден"));
            store.updateUser(userId, password == null ? null : passwords.encode(password), role, active, clock.instant());
        });
        var published = publisher.publishTo(branchId, author);
        return new UserChange(view(store.user(branchId, userId).orElseThrow()), published.version());
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private static ConfigStore.Sections apply(ConfigStore.Sections current, Map<String, Object> patch) {
        return new ConfigStore.Sections(
                patch.containsKey("queue") ? (Map<String, Object>) patch.get("queue") : current.queue(),
                patch.containsKey("bounds") ? (Map<String, Object>) patch.get("bounds") : current.bounds(),
                patch.containsKey("planning") ? (Map<String, Object>) patch.get("planning") : current.planning(),
                patch.containsKey("model") ? (Map<String, Object>) patch.get("model") : current.model(),
                null, null);
    }

    private void requireBranch(String branchId) {
        if (!store.branchExists(branchId)) {
            throw new EsuoException(ErrorCode.BRANCH_NOT_FOUND, "Отделение " + branchId + " не зарегистрировано");
        }
    }

    private BranchConfigStatus status(ConfigStore.BranchState s, ConfigStore.Sections overrides, Instant now) {
        var applied = applied(s);
        boolean pending = s.published() != null && (applied == null || applied < s.published());
        boolean online = !Duration.between(s.lastHeartbeatAt(), now).minus(offlineAfter).isPositive();
        return new BranchConfigStatus(s.id(), online, overrides(overrides), s.published(), applied, s.appliedAt(), pending);
    }

    /** Applied version: {@code config.applied} or heartbeat, whichever is higher. */
    private static @Nullable Integer applied(ConfigStore.BranchState s) {
        int hb = s.heartbeatConfigVersion();
        if (s.applied() == null) { return hb >= 100 ? hb : null; }
        return Math.max(s.applied(), hb);
    }

    private static Overrides overrides(ConfigStore.Sections s) {
        return new Overrides(s.queue(), s.bounds(), s.planning(), s.model());
    }

    private static UserView view(ConfigStore.User u) {
        return new UserView(u.id(), u.login(), u.role(), u.employeeId(), u.fullName(), u.active());
    }

    private static void checkPassword(@Nullable String password, List<FieldViolation> errors) {
        if (password == null || password.length() < 6 || password.length() > 72) {
            errors.add(new FieldViolation("password", "От 6 до 72 символов"));
        }
    }

    private static void checkRole(@Nullable String role, List<FieldViolation> errors) {
        if (role == null || !ConfigMessage.ROLES.contains(role)) {
            errors.add(new FieldViolation("role", "OPERATOR или BRANCH_ADMIN"));
        }
    }
}
