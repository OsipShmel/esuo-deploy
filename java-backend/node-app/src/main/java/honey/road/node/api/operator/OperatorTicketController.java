package honey.road.node.api.operator;

import honey.road.contracts.dto.TicketView;
import honey.road.node.queue.application.OperatorQueries;
import honey.road.node.queue.application.TicketCommands;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Команды оператора над талоном (раздел 9.3). Все принимают необязательный {@code expected_version}:
 * фронтенд обязан его передавать, чтобы действие по устаревшему экрану получило {@code STALE_STATE}.
 */
@RestController
@RequestMapping("/api/v1")
public class OperatorTicketController {
    private final TicketCommands commands;
    private final OperatorQueries queries;

    public OperatorTicketController(TicketCommands commands, OperatorQueries queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @GetMapping("/queue")
    public List<OperatorQueries.QueueItem> queue(@RequestParam(name = "service_id", required = false) UUID serviceId,
                                                 @RequestParam(required = false) String status) {
        return queries.queue(serviceId, status);
    }

    @GetMapping("/tickets/{id}/events")
    public List<Map<String, Object>> events(@PathVariable UUID id) {
        return queries.events(id);
    }

    @PostMapping("/tickets/{id}/check-in")
    public TicketView checkIn(@PathVariable UUID id, @RequestBody(required = false) VersionRequest r, @AuthenticationPrincipal Jwt jwt) {
        return commands.checkIn(id, version(r), Operators.actor(jwt));
    }

    @PostMapping("/tickets/{id}/start")
    public TicketView start(@PathVariable UUID id, @RequestBody(required = false) VersionRequest r, @AuthenticationPrincipal Jwt jwt) {
        return commands.start(id, version(r), Operators.actor(jwt));
    }

    @PostMapping("/tickets/{id}/complete")
    public TicketView complete(@PathVariable UUID id, @RequestBody(required = false) VersionRequest r, @AuthenticationPrincipal Jwt jwt) {
        return commands.complete(id, version(r), Operators.actor(jwt));
    }

    @PostMapping("/tickets/{id}/recall")
    public TicketView recall(@PathVariable UUID id, @RequestBody(required = false) VersionRequest r, @AuthenticationPrincipal Jwt jwt) {
        return commands.recall(id, version(r), Operators.actor(jwt));
    }

    @PostMapping("/tickets/{id}/no-show")
    public TicketView noShow(@PathVariable UUID id, @RequestBody(required = false) VersionRequest r, @AuthenticationPrincipal Jwt jwt) {
        return commands.noShow(id, version(r), Operators.actor(jwt));
    }

    @PostMapping("/tickets/{id}/requeue")
    public TicketView requeue(@PathVariable UUID id, @Valid @RequestBody(required = false) RequeueRequest r,
                              @AuthenticationPrincipal Jwt jwt) {
        return commands.requeue(id, r == null ? null : r.mode(), r == null ? null : r.expectedVersion(), Operators.actor(jwt));
    }

    @PostMapping("/tickets/{id}/redirect")
    public TicketView redirect(@PathVariable UUID id, @Valid @RequestBody RedirectRequest r, @AuthenticationPrincipal Jwt jwt) {
        return commands.redirect(id, r.serviceId(), r.windowId(), r.reason(), r.expectedVersion(), Operators.actor(jwt));
    }

    private static Integer version(VersionRequest r) {
        return r == null ? null : r.expectedVersion();
    }

    public record VersionRequest(Integer expectedVersion) {
    }

    public record RequeueRequest(@Pattern(regexp = "keep_position|to_tail") String mode, Integer expectedVersion) {
    }

    public record RedirectRequest(UUID serviceId, UUID windowId, @Size(max = 500) String reason, Integer expectedVersion) {
    }
}
