package honey.road.http;

public final class EsuoHeaders {

    public static final String REQUEST_ID = "X-Request-Id";
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String DEADLINE = "X-Deadline";
    public static final String NATS_MSG_ID = "Nats-Msg-Id";

    public static final String MDC_REQUEST_ID = "request_id";
    public static final String MDC_BRANCH_ID = "branch_id";

    private EsuoHeaders() {
    }
}
