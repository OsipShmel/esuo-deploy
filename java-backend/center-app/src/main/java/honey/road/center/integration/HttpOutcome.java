package honey.road.center.integration;

/**
 * Outcome of one outbound HTTP attempt (sect 11.3): 2xx delivered; 4xx rejected (retrying is pointless);
 * 5xx, timeout or connection error unavailable (retry later).
 */
public record HttpOutcome(Kind kind, int status, String detail) {
    public enum Kind { DELIVERED, REJECTED, UNAVAILABLE }

    public static HttpOutcome of(int status, String body) {
        var kind = status >= 200 && status < 300 ? Kind.DELIVERED : status >= 400 && status < 500 ? Kind.REJECTED : Kind.UNAVAILABLE;
        return new HttpOutcome(kind, status, "HTTP " + status + (body == null || body.isBlank() ? "" : ": " + abbreviate(body)));
    }

    public static HttpOutcome unavailable(Throwable e) {
        return new HttpOutcome(Kind.UNAVAILABLE, 0, e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
    }

    private static String abbreviate(String s) {
        var one = s.replaceAll("\\s+", " ").strip();
        return one.length() > 200 ? one.substring(0, 200) + "…" : one;
    }
}
