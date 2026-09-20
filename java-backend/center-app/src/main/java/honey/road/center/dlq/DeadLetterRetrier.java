package honey.road.center.dlq;

/** Manual DLQ retry for one {@code kind} ({@code POST /admin/dlq/{id}/retry}). */
public interface DeadLetterRetrier {
    String kind();

    /** Synchronous attempt; {@code null} error means delivered. */
    RetryResult retry(DeadLetters.DeadLetter letter);

    record RetryResult(boolean delivered, String error) {
        public static RetryResult ok() { return new RetryResult(true, null); }
        public static RetryResult failed(String error) { return new RetryResult(false, error); }
    }
}
