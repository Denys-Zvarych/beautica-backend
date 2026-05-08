package com.beautica.auth;

public enum Role {
    CLIENT,
    SALON_OWNER,
    SALON_ADMIN,
    SALON_MASTER,
    INDEPENDENT_MASTER;

    public final String springRole;

    Role() {
        this.springRole = "ROLE_" + name();
    }
}
