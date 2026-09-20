package honey.road.node.security;

/** Node user roles (sect 13.1). BRANCH_ADMIN includes OPERATOR. */
public enum Role {
    OPERATOR,
    BRANCH_ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
