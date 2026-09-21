package honey.road.contracts.event;

/** {@code event_type} (sect 10.1); also the subject suffix after {@code branch.{b}.evt.}. */
public final class EventTypes {
    private EventTypes() {
    }

    public static final String TICKET_CREATED = "ticket.created";
    public static final String TICKET_CHECKED_IN = "ticket.checked_in";
    public static final String TICKET_CALLED = "ticket.called";
    public static final String TICKET_RECALLED = "ticket.recalled";
    public static final String TICKET_SERVING = "ticket.serving";
    public static final String TICKET_COMPLETED = "ticket.completed";
    public static final String TICKET_REQUEUED = "ticket.requeued";
    public static final String TICKET_REDIRECTED = "ticket.redirected";
    public static final String TICKET_POSTPONED = "ticket.postponed";
    public static final String TICKET_CANCELLED = "ticket.cancelled";
    public static final String TICKET_NO_SHOW = "ticket.no_show";

    public static final String WINDOW_OPENED = "window.opened";
    public static final String WINDOW_CLOSING = "window.closing";
    public static final String WINDOW_CLOSED = "window.closed";

    public static final String INCIDENT_CREATED = "incident.created";
    public static final String NOTIFICATION_REQUESTED = "notification.requested";
    public static final String MP_APPOINTMENT_CONFIRMED = "mp.appointment.confirmed";
    public static final String MP_APPOINTMENT_REJECTED = "mp.appointment.rejected";
    public static final String CONFIG_APPLIED = "config.applied";

    /**
     * Published by the branch when applied configuration carries a {@code model.version} it did not have before.
     * Training itself happens offline (ml-model.md 4.2), so this event marks the model arriving at the branch,
     * which is what the center's network view and the demo decision journal care about.
     */
    public static final String MODEL_TRAINED = "model.trained";

    public static final String AGGREGATE_TICKET = "ticket";
    public static final String AGGREGATE_WINDOW = "window";
    public static final String AGGREGATE_INCIDENT = "incident";
    public static final String AGGREGATE_MP = "mp_appointment";
    public static final String AGGREGATE_CONFIG = "config";
    public static final String AGGREGATE_MODEL = "duration_model";
}
