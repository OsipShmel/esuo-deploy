package honey.road.node.monitoring;

import org.jspecify.annotations.Nullable;

import java.util.regex.Pattern;

/** Go {@code time.Duration} strings as NATS monitoring prints them: {@code 1.2ms}, {@code 850µs}, {@code 2m3.5s}. */
public final class GoDuration {
    private static final Pattern PART = Pattern.compile("(\\d+(?:\\.\\d+)?)(ns|us|µs|μs|ms|s|m|h)");
    private static final Pattern WHOLE = Pattern.compile("(?:" + PART.pattern() + ")+");

    private GoDuration() {
    }

    /** @return milliseconds, or {@code null} for anything that is not a Go duration */
    public static @Nullable Double millis(@Nullable String text) {
        if (text == null || !WHOLE.matcher(text).matches()) {
            return null;
        }
        double total = 0;
        var m = PART.matcher(text);
        while (m.find()) {
            double v = Double.parseDouble(m.group(1));
            total += switch (m.group(2)) {
                case "ns" -> v / 1_000_000;
                case "us", "µs", "μs" -> v / 1_000;
                case "ms" -> v;
                case "s" -> v * 1_000;
                case "m" -> v * 60_000;
                default -> v * 3_600_000;                  // h
            };
        }
        return total;
    }
}
