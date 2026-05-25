package com.beautica.auth.dto;

/**
 * Response body for the 409 returned when registration is attempted with an
 * already-registered email AND the
 * {@code app.security.disclose-duplicate-registration} toggle is on (local-dev only).
 *
 * <p>The {@code code} field carries the stable error code
 * {@code EMAIL_ALREADY_REGISTERED}; the client maps it to a localised user-facing
 * message. The email is NOT echoed — the caller sent it, the client already knows
 * it, and omitting it keeps the response payload to the bare minimum the routing
 * logic needs.
 */
public record EmailAlreadyRegisteredResponse(String code) {}
