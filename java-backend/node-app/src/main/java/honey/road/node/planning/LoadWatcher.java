package honey.road.node.planning;

import honey.road.contracts.event.Actor;
import honey.road.contracts.event.EventTypes;
import honey.road.node.config.NodeProperties;
import honey.road.node.persistence.EventRepository;
import honey.road.node.persistence.PolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Moves the load level and records every move (load-planning.md 4.2, 6.2).
 *
 * <p>Runs on a timer rather than on each request, for the same reason {@code QueueTimers} does: the level has to
 * change while nobody is looking, and the hysteresis is measured in wall-clock minutes — in demo mode, minutes
 * of the demo clock, so an accelerated day compresses the holds along with everything else.
 *
 * <p>A level change is an event, not just a row: it closes or opens booking for whole hours, and the decision
 * journal has to be able to answer afterwards why. The event goes into the outbox in the same transaction as the
 * state, so the branch cannot end up having changed level without the center ever hearing about it.
 */
@Component
public class LoadWatcher {
    /** Тик планировщика: обычно 15 с, в демо-режиме 1 с реального времени, как у таймеров очереди. */
    static final String INTERVAL = "#{${esuo.demo-mode:false} ? '1s' : '${esuo.planning.interval:15s}'}";

    private static final Logger log = LoggerFactory.getLogger(LoadWatcher.class);

    private final LoadPlanner planner;
    private final LoadStateRepository state;
    private final PlanningSource planning;
    private final PolicyRepository policies;
    private final EventRepository events;
    private final NodeProperties node;
    private final TransactionTemplate tx;
    private final Clock clock;

    public LoadWatcher(LoadPlanner planner, LoadStateRepository state, PlanningSource planning,
                       PolicyRepository policies, EventRepository events, NodeProperties node,
                       TransactionTemplate tx, Clock clock) {
        this.planner = planner; this.state = state; this.planning = planning; this.policies = policies;
        this.events = events; this.node = node; this.tx = tx; this.clock = clock;
    }

    @Scheduled(fixedDelayString = LoadWatcher.INTERVAL, initialDelayString = "20s")
    public void tick() {
        try {
            apply();
        } catch (RuntimeException e) {
            // Планирование — надстройка над очередью: его падение не должно останавливать зал.
            log.error("load planning pass failed", e);
        }
    }

    /**
     * One pass, in its own transaction.
     *
     * <p>The boundary belongs here rather than in {@link #tick()}: a pass writes the state and the event
     * together, and that pairing is a property of the pass, not of who started it. With the transaction on the
     * caller, every other caller — the demo console, a test — had to know to open one first, and the only
     * feedback was {@code IllegalTransactionState} from deep inside the outbox.
     */
    public LoadLevels.State apply() {
        return tx.execute(status -> pass());
    }

    private LoadLevels.State pass() {
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        var config = planning.current();
        if (!config.usable()) {
            return null;                            // секция planning не опубликована: уровня нет
        }
        var measured = planner.measure();
        var previous = state.load(now).state();
        var next = LoadLevels.next(previous, measured.clearForLevel(), measured.ratioForLevel(),
                config.thresholds(), now);

        boolean changed = !next.level().equals(previous.level());
        if (changed || !sameTimers(previous, next)) {
            state.save(next, now);
        }
        if (changed) {
            publish(previous.level(), next, measured.snapshot(), now);
            log.info("load level {} -> {} clear={} ratio={} open={} needed={}", previous.level(), next.level(),
                    measured.snapshot().clearTimeMin(), measured.snapshot().ratioNow(),
                    measured.snapshot().windows().open(), measured.snapshot().windows().needed());
        }
        return next;
    }

    private void publish(String from, LoadLevels.State next, LoadPlanner.Snapshot snapshot, Instant now) {
        var data = new LinkedHashMap<String, Object>();
        data.put("from", from);
        data.put("to", next.level());
        data.put("clear_time_min", snapshot.clearTimeMin());
        data.put("ratio_now", snapshot.ratioNow());
        var causes = new ArrayList<Object>();
        for (var cause : snapshot.causes()) {
            causes.add(new LinkedHashMap<>(Map.of(
                    "code", cause.code(), "value", cause.value(), "threshold", cause.threshold())));
        }
        data.put("causes", causes);
        if (snapshot.profile() != null) {
            data.put("profile", snapshot.profile().profile().code());
        }
        data.put("windows", new LinkedHashMap<>(Map.of(
                "open", snapshot.windows().open(),
                "planned", snapshot.windows().planned(),
                "needed", snapshot.windows().needed())));
        int configVersion = policies.versions().effective();
        events.otherEvent(node.branchId(), EventTypes.AGGREGATE_LOAD, loadId(), configVersion,
                EventTypes.LOAD_LEVEL_CHANGED, Actor.scheduler(), now, configVersion, data);
    }

    private static boolean sameTimers(LoadLevels.State a, LoadLevels.State b) {
        return Objects.equals(a.upSince(), b.upSince()) && Objects.equals(a.downSince(), b.downSince());
    }

    /** Stable aggregate id of the branch load: one per branch, the level lives inside the events. */
    private UUID loadId() {
        return UUID.nameUUIDFromBytes(("load:" + node.branchId()).getBytes(StandardCharsets.UTF_8));
    }
}
