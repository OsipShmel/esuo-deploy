package honey.road.center.diagnostics;

import honey.road.center.config.CenterProperties;
import honey.road.center.gateway.BranchRpcClient;
import honey.road.center.nats.CenterNats;
import honey.road.contracts.error.ErrorCode;
import honey.road.contracts.error.EsuoException;
import honey.road.contracts.event.Subjects;
import honey.road.contracts.http.EsuoHeaders;
import io.nats.client.api.PublishAck;
import io.nats.client.impl.Headers;
import io.nats.client.impl.NatsMessage;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Full branch logs on request (design 5.7): pages through RPC {@code diag.logs} and publishes the lines into
 * CENTER_LOG ({@code center.log.pull.{b}}) under one pull id; LogShipper takes them to Loki like any other line.
 */
@Service
public class LogPull {
    private static final Logger log = LoggerFactory.getLogger(LogPull.class);
    static final Duration RPC_TIMEOUT = Duration.ofSeconds(10);
    static final int PAGE_LIMIT = 5000;
    static final int DEFAULT_MAX_LINES = 20_000;
    static final int MAX_LINES = 100_000;
    static final int DEFAULT_MINUTES = 30;
    static final Duration MAX_RANGE = Duration.ofDays(7);         // what the branch keeps anyway

    /** Either {@code minutes} back from now, or an explicit {@code from}/{@code to}. */
    public record Request(@Nullable Integer minutes, @Nullable Instant from, @Nullable Instant to,
                          @Nullable String minLevel, @Nullable String contains, @Nullable Integer maxLines) {
        static final Request EMPTY = new Request(null, null, null, null, null, null);
    }

    public record Result(String pullId, String branchId, Instant from, Instant to, int lines, boolean truncated,
                         @Nullable String grafanaUrl) {
    }

    /** The branch answered with its own problem (e.g. {@code LOGS_UNAVAILABLE}): passed through as is. */
    public static final class BranchProblem extends RuntimeException {
        private final transient BranchRpcClient.RpcResult result;

        BranchProblem(BranchRpcClient.RpcResult result) {
            super("branch rpc failed status=" + result.status());
            this.result = result;
        }

        public BranchRpcClient.RpcResult result() { return result; }
    }

    private final BranchRpcClient rpc;
    private final CenterNats nats;
    private final CenterProperties.Logs props;
    private final JsonMapper json;
    private final Clock clock;

    public LogPull(BranchRpcClient rpc, CenterNats nats, CenterProperties props, JsonMapper json, Clock clock) {
        this.rpc = rpc; this.nats = nats; this.props = props.logs(); this.json = json; this.clock = clock;
    }

    public Result pull(String branchId, Request r) {
        var to = r.to() != null ? r.to() : clock.instant();
        var from = r.from() != null ? r.from()
                : to.minus(Duration.ofMinutes(Math.clamp(r.minutes() == null ? DEFAULT_MINUTES : r.minutes(), 1, MAX_RANGE.toMinutes())));
        if (!from.isBefore(to)) {
            throw new EsuoException(ErrorCode.VALIDATION_ERROR, "from должно быть раньше to");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new EsuoException(ErrorCode.VALIDATION_ERROR, "Интервал не больше 7 дней: столько логов хранит отделение");
        }
        int maxLines = Math.clamp(r.maxLines() == null ? DEFAULT_MAX_LINES : r.maxLines(), 1, MAX_LINES);
        var pullId = "p-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        String cursor = null;
        int total = 0;
        boolean truncated = false;
        do {
            var body = new LinkedHashMap<String, Object>();
            body.put("from", from.toString());
            body.put("to", to.toString());
            body.put("min_level", r.minLevel());
            body.put("contains", r.contains());
            body.put("limit", Math.min(PAGE_LIMIT, maxLines - total));
            body.put("cursor", cursor);
            var result = rpc.call(branchId, "diag.logs", body, null, RPC_TIMEOUT);
            if (!result.ok()) {
                throw new BranchProblem(result);
            }
            var lines = new ArrayList<String>();
            result.data().path("lines").forEach(l -> lines.add(l.asString()));
            int room = maxLines - total;
            if (lines.size() > room) {
                lines.subList(room, lines.size()).clear();
                truncated = true;
            }
            publish(branchId, pullId, lines);
            total += lines.size();
            var next = result.data().path("next_cursor");
            cursor = next.isNull() || next.isMissingNode() ? null : next.asString();
            if (cursor != null && total >= maxLines) {
                truncated = true;
                break;
            }
        } while (cursor != null);

        log.info("pulled branch logs branch={} pull={} lines={} truncated={} from={} to={}", branchId, pullId, total,
                truncated, from, to);
        return new Result(pullId, branchId, from, to, total, truncated, grafanaUrl(branchId, pullId, from, to));
    }

    private void publish(String branchId, String pullId, java.util.List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        var connection = nats.connection()
                .orElseThrow(() -> new EsuoException(ErrorCode.INTERNAL_ERROR, "Центр не подключён к NATS"));
        try {
            var js = connection.jetStream();
            var acks = new ArrayList<CompletableFuture<PublishAck>>(lines.size());
            for (var line : lines) {
                var bytes = line.getBytes(StandardCharsets.UTF_8);
                acks.add(js.publishAsync(NatsMessage.builder()
                        .subject(Subjects.centerLogPull(branchId))
                        .headers(new Headers().add(EsuoHeaders.PULL_ID, pullId)
                                .add(EsuoHeaders.NATS_MSG_ID, pullId + ":" + md5(bytes)))
                        .data(bytes)
                        .build()));
            }
            CompletableFuture.allOf(acks.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EsuoException(ErrorCode.INTERNAL_ERROR, "Прервано при сохранении логов");
        } catch (Exception e) {
            log.warn("cannot store pulled logs branch={} pull={}: {}", branchId, pullId, e.toString());
            throw new EsuoException(ErrorCode.INTERNAL_ERROR, "Не удалось сохранить логи отделения в центре");
        }
    }

    private @Nullable String grafanaUrl(String branchId, String pullId, Instant from, Instant to) {
        if (props.grafanaPublicUrl().isEmpty()) {
            return null;
        }
        var query = json.createObjectNode();
        query.put("refId", "A");
        query.put("expr", "{branch=\"" + branchId + "\",pull=\"" + pullId + "\"}");
        query.putObject("datasource").put("type", "loki").put("uid", "loki");
        var pane = json.createObjectNode();
        pane.put("datasource", "loki");
        pane.putArray("queries").add(query);
        pane.putObject("range").put("from", Long.toString(from.toEpochMilli())).put("to", Long.toString(to.toEpochMilli()));
        var panes = json.createObjectNode();
        panes.set("pull", pane);
        return props.grafanaPublicUrl() + "/explore?schemaVersion=1&orgId=1&panes="
                + URLEncoder.encode(json.writeValueAsString(panes), StandardCharsets.UTF_8);
    }

    private static String md5(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
