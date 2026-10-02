package honey.road.center.security;

/** Center API roles (design 4.2). One role for now: the network operator who sees and configures the network. */
public enum CenterRole {
    NETWORK_ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
