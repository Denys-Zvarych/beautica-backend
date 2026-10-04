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

    /**
     * Whether a user of this role can own a {@code masters} row: {@code INDEPENDENT_MASTER},
     * {@code SALON_MASTER} and {@code SALON_OWNER} (owner-as-master) can; {@code CLIENT} and
     * {@code SALON_ADMIN} never do. Roles are immutable after registration, so callers may skip a
     * master-row lookup entirely when this is {@code false}.
     */
    public boolean canOwnMasterRow() {
        return this != CLIENT && this != SALON_ADMIN;
    }
}
