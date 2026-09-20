package honey.road.contracts.event;

/**
 * Who caused the change. {@code type} as in sect 8.1 ({@code ticket_events.actor_type}):
 * client | operator | admin | system | scheduler | center. {@code id} is null for client/system.
 */
public record Actor(String type, String id) {

    public static final String CLIENT = "client";
    public static final String OPERATOR = "operator";
    public static final String ADMIN = "admin";
    public static final String SYSTEM = "system";
    public static final String SCHEDULER = "scheduler";
    public static final String CENTER = "center";

    public Actor {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("actor type is required");
        }
    }

    public static Actor client() { return new Actor(CLIENT, null); }
    public static Actor system() { return new Actor(SYSTEM, null); }
    public static Actor scheduler() { return new Actor(SCHEDULER, null); }
    public static Actor center() { return new Actor(CENTER, null); }
    public static Actor operator(String employeeId) { return new Actor(OPERATOR, employeeId); }
}
