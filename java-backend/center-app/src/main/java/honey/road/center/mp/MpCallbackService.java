package honey.road.center.mp;

import honey.road.center.config.CenterProperties;
import honey.road.center.dlq.DeadLetterRetrier;
import honey.road.center.dlq.DeadLetters;
import honey.road.center.gateway.BranchRpcClient;
import honey.road.center.integration.Breakers;
import honey.road.center.integration.HmacSigner;
import honey.road.center.integration.HttpOutcome;
import honey.road.contracts.error.EsuoException;
import honey.road.contracts.event.EventTypes;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Callback to MP for a branch result (sect 4.6): for {@code confirmed} the ticket token is fetched via RPC
 * {@code ticket.by_mp} and only lives in the callback body as {@code {CENTER_PUBLIC_URL}/b/{b}/t/{token}}.
 * Signed like the webhook. 2xx delivered; 4xx rejected; 5xx, timeout or branch unavailable → retry.
 */
@Service
public class MpCallbackService implements DeadLetterRetrier {

    public enum Result { DELIVERED, REJECTED, UNAVAILABLE, UNKNOWN }

    public record Attempt(Result result, @Nullable String error) {
    }

    private final MpRequests requests;
    private final BranchRpcClient rpc;
    private final CenterProperties.Mp props;
    private final JsonMapper json;
    private final Clock clock;
    private final HttpClient http;
    private final CircuitBreaker breaker;

    MpCallbackService(MpRequests requests, BranchRpcClient rpc, CenterProperties props, JsonMapper json, Clock clock) {
        this.requests = requests; this.rpc = rpc; this.props = props.mp(); this.json = json; this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(this.props.requestTimeout()).build();
        this.breaker = Breakers.of("mp-callback", this.props.circuitBreaker());
    }

    public CircuitBreaker breaker() { return breaker; }

    static String appointmentId(JsonNode event) {
        return event.path("data").path("mp_appointment_id").asString("");
    }

    public Attempt deliver(JsonNode event) {
        var data = event.path("data");
        var id = appointmentId(event);
        var request = requests.get(id);
        if (request.isEmpty()) {
            return new Attempt(Result.UNKNOWN, null);                 // not our webhook: nothing to call back
        }
        if (request.get().status().equals("callback_sent")) {
            return new Attempt(Result.DELIVERED, null);
        }
        var branchId = event.path("branch_id").asString(request.get().branchId());
        boolean confirmed = event.path("event_type").asString("").equals(EventTypes.MP_APPOINTMENT_CONFIRMED);
        var now = clock.instant();
        requests.result(id, confirmed ? "confirmed" : "rejected", data.path("display_number").asString(null),
                confirmed ? null : data.path("reason").asString(null), now);

        Map<String, Object> ticket = null;
        if (confirmed) {
            try {
                var r = rpc.call(branchId, "ticket.by_mp", Map.of("mp_appointment_id", id), null);
                if (!r.ok()) {
                    return failed(id, "ticket.by_mp: HTTP " + r.status());
                }
                ticket = new LinkedHashMap<>();
                ticket.put("display_number", r.data().path("display_number").asString(null));
                ticket.put("status", r.data().path("status").asString(null));
                ticket.put("slot_start", r.data().path("slot_start").asString(null));
                ticket.put("status_url", props.publicUrl() + "/b/" + branchId + "/t/" + r.data().path("token").asString());
            } catch (EsuoException e) {
                return failed(id, "ticket.by_mp: " + e.getMessage());
            }
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("mp_appointment_id", id);
        body.put("branch_id", branchId);
        body.put("result", confirmed ? "confirmed" : "rejected");
        body.put("ticket", ticket);
        body.put("reason", confirmed ? null : data.path("reason").asString(null));
        body.put("detail", confirmed ? null : data.path("detail").asString(null));
        return post(id, json.writeValueAsBytes(body));
    }

    private Attempt post(String id, byte[] body) {
        if (!breaker.tryAcquirePermission()) {
            return failed(id, "circuit breaker open");
        }
        var ts = String.valueOf(clock.instant().getEpochSecond());
        var request = HttpRequest.newBuilder(URI.create(props.callbackUrl()))
                .timeout(props.requestTimeout())
                .header("Content-Type", "application/json")
                .header(HmacSigner.TIMESTAMP, ts)
                .header(HmacSigner.SIGNATURE, HmacSigner.sign(props.webhookSecret(), ts, body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        long started = System.nanoTime();
        HttpOutcome outcome;
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            outcome = HttpOutcome.of(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = HttpOutcome.unavailable(e);
        } catch (Exception e) {
            outcome = HttpOutcome.unavailable(e);
        }
        long elapsed = System.nanoTime() - started;
        switch (outcome.kind()) {
            case DELIVERED -> {
                breaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);
                requests.callback(id, "callback_sent", clock.instant());
                return new Attempt(Result.DELIVERED, null);
            }
            case REJECTED -> {
                breaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);
                requests.callback(id, "callback_failed", clock.instant());
                return new Attempt(Result.REJECTED, outcome.detail());
            }
            default -> {
                breaker.onError(elapsed, TimeUnit.NANOSECONDS, new IllegalStateException(outcome.detail()));
                return failed(id, outcome.detail());
            }
        }
    }

    private Attempt failed(String id, String error) {
        requests.failedAttempt(id, clock.instant());
        return new Attempt(Result.UNAVAILABLE, error);
    }

    void exhausted(String id) {
        requests.result(id, "callback_failed", null, null, clock.instant());
    }

    @Override
    public String kind() { return DeadLetters.MP_CALLBACK; }

    @Override
    public RetryResult retry(DeadLetters.DeadLetter letter) {
        var attempt = deliver(json.readTree(letter.payload()));
        return attempt.result() == Result.DELIVERED ? RetryResult.ok()
                : RetryResult.failed(attempt.error() == null ? attempt.result().name() : attempt.error());
    }
}
