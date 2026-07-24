package com.beautica.auth.dto;

import com.beautica.auth.Role;

/**
 * The subset of {@link Role} values that an unauthenticated caller is permitted
 * to request during self-registration.  Privileged roles (SALON_ADMIN,
 * SALON_MASTER, INDEPENDENT_MASTER) are intentionally absent: any attempt to
 * deserialise a JSON string that does not match these two values will cause
 * Jackson to fail with an unrecognised token error, which Spring MVC maps to
 * HTTP 400 before the request ever reaches service-layer code.
 */
public enum SelfRegistrationRole {
    CLIENT,
    SALON_OWNER;

    public Role toRole() {
        return Role.valueOf(this.name());
    }
}
