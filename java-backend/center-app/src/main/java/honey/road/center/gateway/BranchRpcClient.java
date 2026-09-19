package honey.road.center.gateway;

import honey.road.center.config.CenterProperties;
import honey.road.center.nats.CenterNats;
import honey.road.contracts.error.ErrorCode;
import honey.road.contracts.error.EsuoException;
import honey.road.contracts.event.Subjects;
import honey.road.contracts.http.EsuoHeaders;
import io.nats.client.impl.Headers;
import io.nats.client.impl.NatsMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Синхронный запрос к отделению через NATS request-reply (разделы 4.1, 10.3). Порт {@code BranchRpcClient} (11.1).
 * <ul>
 *   <li>Нет подписчиков на {@code branch.{b}.rpc.>} (отделение offline) — отказ сразу, без ожидания таймаута.</li>
 *   <li>Нет ответа за {@code rpc-timeout} — {@code 503 BRANCH_UNAVAILABLE}.</li>
 *   <li>{@code X-Deadline} сообщает ноде, когда ответ уже никому не нужен.</li>
 * </ul>
 */
@Component
public class BranchRpcClient {
    private static final Logger log = LoggerFactory.getLogger(BranchRpcClient.class);

    private final CenterNats nats;
    private final CenterProperties props;
    private final JsonMapper json;
    private final Clock clock;

    public BranchRpcClient(CenterNats nats, CenterProperties props, JsonMapper json, Clock clock) {
        this.nats = nats; this.props = props; this.json = json; this.clock = clock;
    }

    /** Ответ отделения: HTTP-статус и тело конверта ({@code data} или {@code problem}). */
    public record RpcResult(boolean ok, int status, JsonNode data, JsonNode problem) {
    }

    public RpcResult call(String branchId, String op, Object body, String idempotencyKey) {
        if (branchId == null || !branchId.matches("[a-zA-Z0-9_-]{1,64}")) {
            throw new EsuoException(ErrorCode.BRANCH_NOT_FOUND, "Некорректный код отделения");
        }
        var connection = nats.connection()
                .orElseThrow(() -> unavailable(branchId, "center is not connected to NATS"));
        var headers = new Headers()
                .add(EsuoHeaders.CONTENT_TYPE, "application/json")
                .add(EsuoHeaders.DEADLINE, clock.instant().plus(props.rpcTimeout()).toString());
        var requestId = MDC.get(EsuoHeaders.MDC_REQUEST_ID);
        if (requestId != null) { headers.add(EsuoHeaders.REQUEST_ID, requestId); }
        if (idempotencyKey != null) { headers.add(EsuoHeaders.IDEMPOTENCY_KEY, idempotencyKey); }
        var message = NatsMessage.builder()
                .subject(Subjects.rpc(branchId, op))
                .headers(headers)
                .data(json.writeValueAsString(body == null ? java.util.Map.of() : body).getBytes(StandardCharsets.UTF_8))
                .build();
        try {
            var reply = connection.requestWithTimeout(message, props.rpcTimeout())
                    .get(props.rpcTimeout().toMillis() + 500, TimeUnit.MILLISECONDS);
            if (reply == null || reply.isStatusMessage()) {
                throw unavailable(branchId, "no responders");
            }
            JsonNode envelope = json.readTree(new String(reply.getData(), StandardCharsets.UTF_8));
            return new RpcResult(envelope.path("ok").asBoolean(false), envelope.path("status").asInt(502),
                    envelope.get("data"), envelope.get("problem"));
        } catch (CancellationException | TimeoutException e) {
            throw unavailable(branchId, e.getClass().getSimpleName());
        } catch (ExecutionException e) {
            throw unavailable(branchId, String.valueOf(e.getCause()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable(branchId, "interrupted");
        }
    }

    private EsuoException unavailable(String branchId, String reason) {
        log.warn("branch rpc unavailable branch={} reason={}", branchId, reason);
        return new EsuoException(ErrorCode.BRANCH_UNAVAILABLE,
                "Отделение " + branchId + " сейчас недоступно. Повторите позже — повтор с тем же Idempotency-Key безопасен");
    }
}
