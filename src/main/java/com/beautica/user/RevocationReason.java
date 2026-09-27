package com.beautica.user;

/**
 * Why an {@link InviteToken} stopped being actionable through a path OTHER than acceptance.
 *
 * <p>Exists because {@code is_used = true} is written by BOTH acceptance
 * ({@code InviteService#acceptInvite}) and cancellation ({@code SalonService#cancelInvite}), so
 * that flag alone cannot tell ACCEPTED from CANCELLED. This is the one bit the wall clock cannot
 * derive — PENDING vs EXPIRED remains a pure function of {@code expires_at} and the injected
 * {@code Clock}, which is why no expiry sweeper job exists or is needed.
 *
 * <p>Mirrored by the {@code ck_invite_tokens_revoked_reason} CHECK constraint (V153); adding a
 * constant here requires a migration widening that constraint.
 */
public enum RevocationReason {

    /**
     * A salon owner/admin revoked the invite via {@code DELETE /salons/{salonId}/invites/{id}}.
     * The row ALSO carries {@code is_used = true} so every pre-existing "already consumed?" guard
     * (accept, preview, double-cancel) keeps rejecting it unchanged.
     */
    CANCELLED,

    /**
     * The invite expired unused and was retired to free the {@code ux_invite_tokens_active} slot
     * for a fresh invite to the same {@code (salon, lower(email))}. Keeps {@code is_used = false}
     * — it was never accepted — and is classified EXPIRED for display.
     */
    SUPERSEDED
}
