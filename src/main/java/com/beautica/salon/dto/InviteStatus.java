package com.beautica.salon.dto;

/**
 * Display status of a salon invite on {@code GET /api/v1/salons/{salonId}/invites}.
 *
 * <p>DERIVED on read, never stored. Two of the four states are functions of the wall clock
 * ({@link #PENDING} becomes {@link #EXPIRED} with no write ever touching the row), so persisting
 * this enum would require an expiry sweeper job that deliberately does not exist, and would
 * duplicate state {@code is_used} and {@code revoked_reason} already hold. See
 * {@link SalonInviteResponse#from} for the derivation and its load-bearing ordering.
 *
 * <p>Package-private on purpose: it is an implementation detail of the response shape. The wire
 * carries {@code status} as a plain {@code String} ({@link #name()}), matching the existing
 * {@code role} convention on the same record.
 */
enum InviteStatus {

    /** Not accepted, not revoked, and {@code expiresAt} is still in the future. */
    PENDING,

    /** The recipient completed {@code POST /auth/invite/accept} and an account was provisioned. */
    ACCEPTED,

    /** Never accepted; either it timed out, or it was retired to free the slot for a re-invite. */
    EXPIRED,

    /** Revoked by a salon owner/admin via {@code DELETE /salons/{salonId}/invites/{inviteId}}. */
    CANCELLED
}
