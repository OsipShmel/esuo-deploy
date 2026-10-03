package honey.road.node.monitoring;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Lifecycle lines carry {@code event.kind=lifecycle} and {@code event.action}: the branch collector ships them by that mark. */
class LifecycleTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger("lifecycle-test");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private static Map<String, Object> pairs(ILoggingEvent e) {
        return e.getKeyValuePairs().stream().collect(Collectors.toMap(p -> p.key, p -> p.value));
    }

    @Test
    void infoCarriesKindAndAction() {
        Lifecycle.info(logger, "nats.connected", "nats connected url={}", "nats://leaf:4222");

        var e = appender.list.getFirst();
        assertThat(e.getLevel()).isEqualTo(Level.INFO);
        assertThat(e.getFormattedMessage()).isEqualTo("nats connected url=nats://leaf:4222");
        assertThat(pairs(e)).containsEntry("event.kind", "lifecycle").containsEntry("event.action", "nats.connected");
    }

    @Test
    void warnKeepsLevel() {
        Lifecycle.warn(logger, "leaf.unlinked", "leaf is not linked to the hub");

        assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void errorCarriesCause() {
        Lifecycle.error(logger, "error.unhandled", new IllegalStateException("boom"), "scheduled task failed");

        var e = appender.list.getFirst();
        assertThat(e.getLevel()).isEqualTo(Level.ERROR);
        assertThat(e.getThrowableProxy().getMessage()).isEqualTo("boom");
        assertThat(pairs(e)).containsEntry("event.action", "error.unhandled");
    }

    @Test
    void noOtherPairsAreAdded() {
        Lifecycle.info(logger, "app.started", "started");

        assertThat(appender.list.getFirst().getKeyValuePairs()).extracting(p -> p.key)
                .containsExactly("event.kind", "event.action");
    }

}
