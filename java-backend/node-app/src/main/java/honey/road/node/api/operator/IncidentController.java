package honey.road.node.api.operator;

import honey.road.node.queue.application.Incidents;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import honey.road.node.security.CurrentUser;

/**
 * Incidents (sect 9.3): {@code POST /incidents} for operator and admin; {@code PATCH /admin/incidents/{id}} admin only.
 */
@RestController
@RequestMapping("/api/v1")
public class IncidentController {
    private final Incidents incidents;

    public IncidentController(Incidents incidents) {
        this.incidents = incidents;
    }

    public record CreateRequest(@NotBlank @Pattern(regexp = "technical|operational") String kind,
                                @NotBlank @Size(max = 1000) String description, UUID windowId, UUID ticketId) {
    }

    public record ResolveRequest(@Pattern(regexp = "resolved") String status) {
    }

    @PostMapping("/incidents")
    @ResponseStatus(HttpStatus.CREATED)
    public Incidents.IncidentView create(@Valid @RequestBody CreateRequest r, @AuthenticationPrincipal Jwt jwt) {
        var actor = Operators.actor(jwt);
        var employee = CurrentUser.of(jwt).employeeId();
        return incidents.create(r.kind(), r.description(), r.windowId(), r.ticketId(), employee, actor);
    }

    @PatchMapping("/admin/incidents/{id}")
    public Incidents.IncidentView resolve(@PathVariable UUID id, @Valid @RequestBody ResolveRequest r) {
        return incidents.resolve(id);
    }
}
