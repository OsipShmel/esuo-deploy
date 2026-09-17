package honey.road.api.operator;

import honey.road.TicketView;
import honey.road.queue.application.IssueTicket;
import honey.road.queue.application.IssueTicketCommand;
import honey.road.queue.domain.DomainException;
import honey.road.queue.domain.TicketSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Оператор добавляет клиента из живой очереди. Аутентификация — JWT (роль OPERATOR и выше),
 * проверяется SecurityConfig до входа в метод.
 */
@RestController
@RequestMapping("/api/v1/windows")
public class WalkinController {
    private final IssueTicket issue;

    public WalkinController(IssueTicket issue) {
        this.issue = issue;
    }

    @PostMapping("/{id}/walkin")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketView walkin(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                             @Valid @RequestBody WalkinRequest request) {
        if (Boolean.TRUE.equals(request.callNow())) {
            throw DomainException.validation("Немедленный вызов будет доступен после реализации Queue Engine в День 2");
        }
        return issue.execute(new IssueTicketCommand(TicketSource.walkin, request.serviceId(), null,
                request.clientCategory(), null, id), key);
    }

    public record WalkinRequest(@NotNull UUID serviceId, @Size(max = 64) String clientCategory, Boolean callNow) {
    }
}
