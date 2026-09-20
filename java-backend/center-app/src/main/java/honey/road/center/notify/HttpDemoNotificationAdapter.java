package honey.road.center.notify;

import honey.road.center.config.CenterProperties;
import honey.road.center.integration.HttpOutcome;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** HTTP adapter for notify-mock (sect 11.1): {@code POST {url}} with {@code Idempotency-Key = dedup_key}. */
@Component
class HttpDemoNotificationAdapter implements NotificationGateway {
    private final CenterProperties.Notify props;
    private final JsonMapper json;
    private final HttpClient http;

    HttpDemoNotificationAdapter(CenterProperties props, JsonMapper json) {
        this.props = props.notify();
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(this.props.connectTimeout()).build();
    }

    @Override
    public DeliveryResult send(NotificationMessage message) {
        var request = HttpRequest.newBuilder(URI.create(props.url()))
                .timeout(props.requestTimeout())
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", message.dedupKey())
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(message)))
                .build();
        HttpOutcome outcome;
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            outcome = HttpOutcome.of(response.statusCode(), response.body());
            if (outcome.kind() == HttpOutcome.Kind.DELIVERED) {
                return new DeliveryResult(response.statusCode(), response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotificationUnavailableException("interrupted");
        } catch (Exception e) {
            outcome = HttpOutcome.unavailable(e);
        }
        if (outcome.kind() == HttpOutcome.Kind.REJECTED) {
            throw new NotificationRejectedException(outcome.detail());
        }
        throw new NotificationUnavailableException(outcome.detail());
    }
}
