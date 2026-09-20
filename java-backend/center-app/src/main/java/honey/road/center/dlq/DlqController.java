package honey.road.center.dlq;

import honey.road.contracts.error.ErrorCode;
import honey.road.contracts.error.EsuoException;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** DLQ for the network monitor (sect 9.5). Open until center roles exist. */
@RestController
@RequestMapping("/api/v1/admin")
class DlqController {
    private final DeadLetters letters;
    private final Map<String, DeadLetterRetrier> retriers;
    private final JsonMapper json;
    private final Clock clock;

    DlqController(DeadLetters letters, List<DeadLetterRetrier> retriers, JsonMapper json, Clock clock) {
        this.letters = letters; this.json = json; this.clock = clock;
        this.retriers = retriers.stream().collect(Collectors.toMap(DeadLetterRetrier::kind, Function.identity()));
    }

    record DeadLetterView(long id, String kind, String key, String branchId, JsonNode payload, int attempts, String reason,
                          @Nullable String lastError, Instant createdAt, @Nullable Instant resolvedAt) {
    }

    record RetryResponse(String result, @Nullable String error) {
    }

    /** {@code kind=notification|mp_callback}, {@code status=open|resolved}. */
    @GetMapping("/dlq")
    List<DeadLetterView> list(@RequestParam(required = false) @Nullable String kind,
                              @RequestParam(defaultValue = "open") String status,
                              @RequestParam(defaultValue = "100") int limit) {
        if (kind != null && !retriers.containsKey(kind)) {
            throw new EsuoException(ErrorCode.VALIDATION_ERROR, "kind: notification | mp_callback");
        }
        if (!status.equals("open") && !status.equals("resolved")) {
            throw new EsuoException(ErrorCode.VALIDATION_ERROR, "status: open | resolved");
        }
        return letters.list(kind, status.equals("resolved"), Math.clamp(limit, 1, 500)).stream().map(this::view).toList();
    }

    @GetMapping("/notifications/dlq")
    List<DeadLetterView> notifications(@RequestParam(defaultValue = "open") String status) {
        return list(DeadLetters.NOTIFICATION, status, 100);
    }

    /** Synchronous resend: {@code {result: delivered}} closes the entry, {@code {result: failed, error}} keeps it. */
    @PostMapping("/dlq/{id}/retry")
    RetryResponse retry(@PathVariable long id) {
        var letter = letters.get(id).orElseThrow(() -> new EsuoException(ErrorCode.NOT_FOUND, "Запись DLQ не найдена"));
        if (letter.resolvedAt() != null) {
            return new RetryResponse("delivered", null);
        }
        var result = retriers.get(letter.kind()).retry(letter);
        if (result.delivered()) {
            letters.resolve(id, clock.instant());
            return new RetryResponse("delivered", null);
        }
        letters.failedRetry(id, result.error());
        return new RetryResponse("failed", result.error());
    }

    private DeadLetterView view(DeadLetters.DeadLetter d) {
        return new DeadLetterView(d.id(), d.kind(), d.key(), d.branchId(), json.readTree(d.payload()), d.attempts(),
                d.reason(), d.lastError(), d.createdAt(), d.resolvedAt());
    }
}
