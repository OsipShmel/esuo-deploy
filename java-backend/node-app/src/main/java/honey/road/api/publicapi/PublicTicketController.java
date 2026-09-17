package honey.road.api.publicapi;

import honey.road.TicketView;
import honey.road.config.NodeProperties;
import honey.road.queue.application.CancelTicket;
import honey.road.queue.application.IssueTicket;
import honey.road.queue.application.IssueTicketCommand;
import honey.road.queue.application.TicketQueries;
import honey.road.queue.domain.DomainException;
import honey.road.queue.domain.TicketSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Клиент и табло: без аутентификации, доступ к талону — по секретному токену.
 * Всё под /api/v1/public/** — SecurityConfig открывает только этот префикс.
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicTicketController {
    private final IssueTicket issue;
    private final TicketQueries queries;
    private final CancelTicket cancel;
    private final NodeProperties node;

    public PublicTicketController(IssueTicket issue, TicketQueries queries, CancelTicket cancel, NodeProperties node) {
        this.issue = issue;
        this.queries = queries;
        this.cancel = cancel;
        this.node = node;
    }

    @GetMapping("/branch")
    public Map<String, Object> branch() {
        return queries.branch();
    }

    @GetMapping("/slots")
    public List<TicketQueries.SlotView> slots(@RequestParam("service_id") UUID service, @RequestParam LocalDate date) {
        return queries.slots(service, date);
    }

    @PostMapping("/appointments")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketView appointment(@RequestHeader("Idempotency-Key") String key,
                                  @Valid @RequestBody AppointmentRequest request) {
        return issue.execute(new IssueTicketCommand(TicketSource.appointment, request.serviceId(), request.slotStart(),
                request.clientCategory(), null, null), key);
    }

    @PostMapping("/qr-tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketView qr(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody QrRequest request) {
        // Демонстрационный код обозначает отделение; подпись и ротация (13.4) — позже, адаптер B.
        if (!node.demoData() || !("demo:" + node.branchId()).equals(request.qrCode())) {
            throw new DomainException("QR_CODE_INVALID", "Демонстрационный QR-код недействителен");
        }
        return issue.execute(new IssueTicketCommand(TicketSource.qr, request.serviceId(), null,
                request.clientCategory(), request.deviceId(), null), key);
    }

    @GetMapping("/tickets/{token}")
    public TicketView get(@PathVariable UUID token) {
        return queries.get(token);
    }

    @PostMapping("/tickets/{token}/cancel")
    public TicketView cancel(@PathVariable UUID token) {
        return cancel.execute(token);
    }

    public record AppointmentRequest(@NotNull UUID serviceId, @NotNull Instant slotStart,
                                     @Size(max = 64) String clientCategory) {
    }

    public record QrRequest(@NotBlank @Size(max = 128) String qrCode, @NotNull UUID serviceId,
                            @Size(max = 64) String clientCategory, @NotBlank @Size(max = 128) String deviceId) {
    }
}
