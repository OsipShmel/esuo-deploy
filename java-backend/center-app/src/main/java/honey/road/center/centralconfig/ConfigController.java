package honey.road.center.centralconfig;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service manager API (sect 9.5). Open until center roles exist (sect 13.2); the author is recorded as
 * {@code service_manager}.
 */
@RestController
@RequestMapping("/api/v1")
class ConfigController {
    private static final String AUTHOR = "service_manager";

    private final ConfigService config;

    ConfigController(ConfigService config) { this.config = config; }

    record CreateUser(@Nullable String login, @Nullable String password, @Nullable String role, @Nullable String fullName) {
    }

    record UpdateUser(@Nullable String password, @Nullable String role, @Nullable Boolean active) {
    }

    /** Global sections and a page of branches: {@code q} — part of the id or name, {@code region} — events_via or own. */
    @GetMapping("/config")
    ConfigService.ConfigOverview overview(@RequestParam(required = false) @Nullable String q,
                                          @RequestParam(required = false) @Nullable String region,
                                          @RequestParam(defaultValue = "0") int offset,
                                          @RequestParam(defaultValue = "50") int limit) {
        return config.overview(q, region, offset, limit);
    }

    /** Body {@code {queue?, bounds?, planning?}}: an absent section is unchanged, {@code null} clears it. */
    @PutMapping("/config/global")
    ConfigPublisher.Published putGlobal(@RequestBody Map<String, Object> body) {
        return config.putGlobal(body, AUTHOR);
    }

    @GetMapping("/config/branches/{branchId}")
    ConfigService.BranchConfigView branch(@PathVariable String branchId) {
        return config.branch(branchId);
    }

    /** Branch overrides, same body as the global one; a cleared section falls back to the global value. */
    @PutMapping("/config/branches/{branchId}")
    ConfigPublisher.Published putBranch(@PathVariable String branchId, @RequestBody Map<String, Object> body) {
        return config.putBranch(branchId, body, AUTHOR);
    }

    @GetMapping("/config/versions")
    List<ConfigService.VersionView> versions(@RequestParam(required = false) @Nullable String branch,
                                             @RequestParam(defaultValue = "50") int limit) {
        return config.versions(branch, limit);
    }

    @GetMapping("/branches/{branchId}/users")
    List<ConfigService.UserView> users(@PathVariable String branchId) {
        return config.users(branchId);
    }

    @PostMapping("/branches/{branchId}/users")
    @ResponseStatus(HttpStatus.CREATED)
    ConfigService.UserChange createUser(@PathVariable String branchId, @RequestBody CreateUser body) {
        return config.createUser(branchId, body.login(), body.password(), body.role(), body.fullName(), AUTHOR);
    }

    @PatchMapping("/branches/{branchId}/users/{userId}")
    ConfigService.UserChange updateUser(@PathVariable String branchId, @PathVariable UUID userId, @RequestBody UpdateUser body) {
        return config.updateUser(branchId, userId, body.password(), body.role(), body.active(), AUTHOR);
    }
}
