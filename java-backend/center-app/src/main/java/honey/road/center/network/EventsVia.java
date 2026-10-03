package honey.road.center.network;

import honey.road.contracts.event.Subjects;
import org.jspecify.annotations.Nullable;

/**
 * {@code events_via} of a heartbeat: the regional JetStream domain that holds the branch's events. Anything that is
 * not a {@code region-N} domain counts as absent — the branch is then sourced through its own leaf as before, and a
 * typo in a heartbeat can never point {@code CENTER_EVT} at the hub's own domain.
 */
final class EventsVia {
    private EventsVia() {
    }

    static @Nullable String of(@Nullable String value) {
        return value != null && value.matches(Subjects.REGION_DOMAIN_PATTERN) ? value : null;
    }
}
