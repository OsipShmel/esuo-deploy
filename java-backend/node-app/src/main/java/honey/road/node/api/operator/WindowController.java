package honey.road.node.api.operator;

import honey.road.contracts.dto.TicketView;
import honey.road.contracts.dto.WindowView;
import honey.road.node.queue.application.CallNext;
import honey.road.node.queue.application.Walkin;
import honey.road.node.queue.application.WindowCommands;
import honey.road.node.queue.application.WindowViews;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Windows and calls (Section 9.3, operator) */
@RestController
@RequestMapping("/api/v1/windows")
public class WindowController {
    private final WindowViews views;
    private final WindowCommands commands;
    private final CallNext callNext;
    private final Walkin walkin;

    public WindowController(WindowViews views, WindowCommands commands, CallNext callNext, Walkin walkin) {
        this.views = views; this.commands = commands; this.callNext = callNext; this.walkin = walkin;
    }

    @GetMapping
    public List<WindowView> list() {
        return views.all();
    }

    @GetMapping("/{id}")
    public WindowView get(@PathVariable UUID id) {
        return views.get(id);
    }

    @PostMapping("/{id}/open")
    public WindowView open(@PathVariable UUID id, @Valid @RequestBody OpenRequest request, @AuthenticationPrincipal Jwt jwt) {
        return commands.open(id, request.serviceIds(), Operators.employee(jwt));
    }

    @PostMapping("/{id}/close")
    public WindowView close(@PathVariable UUID id, @Valid @RequestBody(required = false) CloseRequest request,
                            @AuthenticationPrincipal Jwt jwt) {
        return commands.close(id, request == null ? null : request.mode(), Operators.employee(jwt));
    }

    @PostMapping("/{id}/call-next")
    public TicketView callNext(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return callNext.execute(id, Operators.employee(jwt));
    }

    @PostMapping("/{id}/walkin")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketView walkin(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                             @Valid @RequestBody WalkinRequest request, @AuthenticationPrincipal Jwt jwt) {
        return walkin.execute(id, request.serviceId(), request.clientCategory(), Boolean.TRUE.equals(request.callNow()),
                Operators.employee(jwt), key);
    }

    public record OpenRequest(@NotEmpty @Size(max = 32) List<@NotNull UUID> serviceIds) {
    }

    public record CloseRequest(@Pattern(regexp = "graceful|force") String mode) {
    }

    public record WalkinRequest(@NotNull UUID serviceId, @Size(max = 64) String clientCategory, Boolean callNow) {
    }
}
