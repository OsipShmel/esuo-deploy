package honey.road.node.api.publicapi;

import honey.road.contracts.dto.TicketView;
import honey.road.node.queue.application.BoardQueries;
import honey.road.node.queue.application.CancelTicket;
import honey.road.node.queue.application.TicketCommands;
import honey.road.node.queue.application.IssueTicket;
import honey.road.node.queue.application.IssueTicketCommand;
import honey.road.node.queue.application.TicketQueries;
import honey.road.node.queue.domain.TicketSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
    * Client and display: no authentication; access to the ticket via a secret token.
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicTicketController {
    private final IssueTicket issue;
    private final TicketQueries queries;
    private final CancelTicket cancel;
    private final TicketCommands commands;
    private final BoardQueries board;
    private final QrCodes qr;

    public PublicTicketController(IssueTicket issue, TicketQueries queries, CancelTicket cancel,
                                  TicketCommands commands, BoardQueries board, QrCodes qr) {
        this.issue = issue;
        this.queries = queries;
        this.cancel = cancel;
        this.commands = commands;
        this.board = board;
        this.qr = qr;
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
        qr.verify(request.qrCode());
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

    /** Confirmation of scheduled arrival: a QR code at the branch proves the client is on-site(стоит пересмотреть) */
    @PostMapping("/tickets/{token}/check-in")
    public TicketView checkIn(@PathVariable UUID token, @Valid @RequestBody CheckInRequest request) {
        qr.verify(request.qrCode());
        return commands.checkInByToken(token);
    }

    @PostMapping("/tickets/{token}/postpone")
    public TicketView postpone(@PathVariable UUID token, @Valid @RequestBody PostponeRequest request) {
        return commands.postpone(token, request.minutes());
    }

    @GetMapping("/board")
    public BoardQueries.Board board() {
        return board.board(qr.current());
    }

    public record CheckInRequest(@NotBlank @Size(max = 256) String qrCode) {
    }

    public record PostponeRequest(@NotNull @Min(1) @Max(120) Integer minutes) {
    }

    public record AppointmentRequest(@NotNull UUID serviceId, @NotNull Instant slotStart,
                                     @Size(max = 64) String clientCategory) {
    }

    public record QrRequest(@NotBlank @Size(max = 128) String qrCode, @NotNull UUID serviceId,
                            @Size(max = 64) String clientCategory, @NotBlank @Size(max = 128) String deviceId) {
    }
}
