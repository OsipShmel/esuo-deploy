package honey.road.queue.domain;

public class DomainException extends RuntimeException {
    private final String code;

    public DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }

    public static DomainException validation(String message) {
        return new DomainException("VALIDATION_ERROR", message);
    }
}
