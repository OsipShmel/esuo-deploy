package honey.road.node.demo;

import honey.road.contracts.error.ErrorCode;
import honey.road.contracts.error.EsuoException;
import honey.road.node.config.NodeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes one log line of the requested level on demand, demo mode only: lets the scenarios and the live demo show an
 * ERROR travelling to the center without breaking anything real.
 */
@Component
public class DemoLog {
    private static final Logger log = LoggerFactory.getLogger(DemoLog.class);
    private static final int MAX_MESSAGE = 500;

    private final NodeProperties node;

    public DemoLog(NodeProperties node) { this.node = node; }

    public void write(String level, String message) {
        if (!node.demoMode()) {
            throw new EsuoException(ErrorCode.NOT_FOUND, "Демо-режим выключен (DEMO_MODE=false)");
        }
        if (message == null || message.isBlank() || message.length() > MAX_MESSAGE) {
            throw new EsuoException(ErrorCode.VALIDATION_ERROR, "message: от 1 до " + MAX_MESSAGE + " символов");
        }
        var builder = switch (level == null ? "" : level) {
            case "error" -> log.atError();
            case "warn" -> log.atWarn();
            case "info" -> log.atInfo();
            default -> throw new EsuoException(ErrorCode.VALIDATION_ERROR, "level: error, warn или info");
        };
        builder.addKeyValue("event.action", "demo.log").log("demo log line: {}", message);
    }
}
