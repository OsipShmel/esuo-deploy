package honey.road.center.logs;

import honey.road.center.config.CenterProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/** Thin HTTP client of the Loki push API. Never throws: every failure is an outcome the shipper acts on. */
@Component
public class LokiClient {

    public enum Outcome {
        /** Loki took the batch. */
        OK,
        /** Loki refused the content itself (too old, too long): a retry would get the same answer. */
        REJECTED,
        /** Loki is down, overloaded or misconfigured: keep the batch and try later. */
        RETRY
    }

    public record Result(Outcome outcome, int status, String detail) {
    }

    private final CenterProperties.Logs props;
    private final HttpClient http;

    public LokiClient(CenterProperties props) {
        this.props = props.logs();
        this.http = HttpClient.newBuilder().connectTimeout(this.props.connectTimeout()).build();
    }

    public Result push(String json) {
        var request = HttpRequest.newBuilder(URI.create(props.lokiUrl() + "/loki/api/v1/push"))
                .timeout(props.requestTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            var detail = response.body() == null ? "" : response.body().strip();
            if (status / 100 == 2) {
                return new Result(Outcome.OK, status, "");
            }
            return new Result(status == 400 ? Outcome.REJECTED : Outcome.RETRY, status,
                    detail.length() > 300 ? detail.substring(0, 300) : detail);
        } catch (IOException e) {
            return new Result(Outcome.RETRY, 0, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Outcome.RETRY, 0, "interrupted");
        }
    }
}
