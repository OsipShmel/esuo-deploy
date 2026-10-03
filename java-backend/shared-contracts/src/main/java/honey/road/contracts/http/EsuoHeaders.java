package honey.road.contracts.http;

public final class EsuoHeaders {

    public static final String REQUEST_ID = "X-Request-Id";
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String DEADLINE = "X-Deadline";
    public static final String NATS_MSG_ID = "Nats-Msg-Id";
    /** Which on-demand log pull a line belongs to (center.log.pull.{b}). */
    public static final String PULL_ID = "Esuo-Pull-Id";
    public static final String CONTENT_TYPE = "Content-Type";

    public static final String MDC_REQUEST_ID = "request_id";
    public static final String MDC_BRANCH_ID = "branch_id";

    private EsuoHeaders() {
    }
}
