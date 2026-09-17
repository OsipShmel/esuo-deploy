package ru.esuo.contracts.event;

/** {@code id} = null for system/scheduler/client. */
public record Actor(Type type, String id) {

    public enum Type { OPERATOR, CLIENT, SYSTEM, SCHEDULER, MP }

    public static Actor system() {
        return new Actor(Type.SYSTEM, null);
    }
}
