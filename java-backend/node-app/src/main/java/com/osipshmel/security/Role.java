package com.osipshmel.security;

/** Это роли только пользователей ноды из раздела 13.1. BRANCH_ADMIN включает права OPERATOR. */
public enum Role {
    OPERATOR,
    BRANCH_ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
