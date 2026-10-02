package honey.road.node.monitoring;

import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Lifecycle log lines: start and stop, broker and leaf links, config applied, unhandled errors. They carry
 * {@code event.kind=lifecycle} and {@code event.action}; the branch collector ships them to the center whatever the
 * level, next to WARN and ERROR (design 5.2).
 */
public final class Lifecycle {
    public static final String KIND = "lifecycle";

    private Lifecycle() {
    }

    public static void info(Logger log, String action, String message, Object... args) {
        mark(log.atInfo(), action).log(message, args);
    }

    public static void warn(Logger log, String action, String message, Object... args) {
        mark(log.atWarn(), action).log(message, args);
    }

    public static void error(Logger log, String action, Throwable cause, String message, Object... args) {
        mark(log.atError(), action).setCause(cause).log(message, args);
    }

    private static LoggingEventBuilder mark(LoggingEventBuilder builder, String action) {
        return builder.addKeyValue("event.kind", KIND).addKeyValue("event.action", action);
    }
}
