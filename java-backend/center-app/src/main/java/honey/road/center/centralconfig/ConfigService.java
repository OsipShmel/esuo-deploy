package honey.road.center.centralconfig;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Service manager use cases (sect 9.5): global config, branch overrides, branch users. Every change publishes a version. */
@Service
public class ConfigService {
    private static final String LOGIN = "[a-zA-Z0-9._-]{3,64}";

    private final ConfigStore store;
    private final ConfigPublisher publisher;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final PasswordEncoder passwords = new BCryptPasswordEncoder();

    ConfigService(ConfigStore store, ConfigPublisher publisher, TransactionTemplate tx, Clock clock) {
        this.store = store; this.publisher = publisher; this.tx = tx; this.clock = clock;
    }

    // ------------------------------------------------------------------ views

    public record SectionsView(Map<String, Object> queue, Map<String, Object> bounds, Map<String, Object> planning,
                               @Nullable Instant updatedAt, @Nullable String updatedBy) {
    }

    public record Overrides(@Nullable Map<String, Object> queue, @Nullable Map<String, Object> bounds,
                            @Nullable Map<String, Object> planning) {
    }

    public record BranchConfigStatus(String branchId, Overrides overrides, @Nullable Integer publishedVersion,
                                     @Nullable Integer appliedVersion, @Nullable Instant appliedAt, boolean pending) {
    }

    public record ConfigOverview(SectionsView global, List<BranchConfigStatus> branches) {
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

    public ConfigOverview overview() {
        var g = store.global();
        var global = new SectionsView(g.queue(), g.bounds(), g.planning(), g.updatedAt(), g.updatedBy());
        var branches = store.states().stream().map(s -> status(s, store.branch(s.id()))).toList();
        return new ConfigOverview(global, branches);
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
                null, null);
    }

    private void requireBranch(String branchId) {
        if (!store.branchExists(branchId)) {
            throw new EsuoException(ErrorCode.BRANCH_NOT_FOUND, "Отделение " + branchId + " не зарегистрировано");
        }
    }

    private static BranchConfigStatus status(ConfigStore.BranchState s, ConfigStore.Sections overrides) {
        var applied = applied(s);
        boolean pending = s.published() != null && (applied == null || applied < s.published());
        return new BranchConfigStatus(s.id(), overrides(overrides), s.published(), applied, s.appliedAt(), pending);
    }

    /** Applied version: {@code config.applied} or heartbeat, whichever is higher. */
    private static @Nullable Integer applied(ConfigStore.BranchState s) {
        int hb = s.heartbeatConfigVersion();
        if (s.applied() == null) { return hb >= 100 ? hb : null; }
        return Math.max(s.applied(), hb);
    }

    private static Overrides overrides(ConfigStore.Sections s) {
        return new Overrides(s.queue(), s.bounds(), s.planning());
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
