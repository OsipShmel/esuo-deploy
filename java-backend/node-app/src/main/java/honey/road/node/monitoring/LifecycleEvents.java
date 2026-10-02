package honey.road.node.monitoring;

import honey.road.node.config.NodeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.task.SimpleAsyncTaskSchedulerCustomizer;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.util.ErrorHandler;

/**
 * Start, stop and failed scheduled tasks as lifecycle lines (design 5.2). A failed {@code @Scheduled} run is otherwise
 * a plain stack trace from the scheduler; with virtual threads Spring Boot uses {@code SimpleAsyncTaskScheduler},
 * otherwise a thread pool — both get the same handler.
 */
@Configuration(proxyBeanMethods = false)
class LifecycleEvents {
    private static final Logger log = LoggerFactory.getLogger(LifecycleEvents.class);

    private final NodeProperties node;

    LifecycleEvents(NodeProperties node) { this.node = node; }

    @EventListener(ApplicationReadyEvent.class)
    void started() {
        Lifecycle.info(log, "app.started", "node started branch={} version={} demo_mode={} demo_data={}",
                node.branchId(), version(), node.demoMode(), node.demoData());
    }

    @EventListener(ContextClosedEvent.class)
    void stopping() {
        Lifecycle.info(log, "app.stopping", "node stopping branch={}", node.branchId());
    }

    static final ErrorHandler SCHEDULED_ERRORS =
            t -> Lifecycle.error(log, "error.unhandled", t, "scheduled task failed: {}", t.toString());

    @Bean
    SimpleAsyncTaskSchedulerCustomizer lifecycleSimpleSchedulerErrors() {
        return scheduler -> scheduler.setErrorHandler(SCHEDULED_ERRORS);
    }

    @Bean
    ThreadPoolTaskSchedulerCustomizer lifecyclePoolSchedulerErrors() {
        return scheduler -> scheduler.setErrorHandler(SCHEDULED_ERRORS);
    }

    static String version() {
        var v = LifecycleEvents.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }
}
