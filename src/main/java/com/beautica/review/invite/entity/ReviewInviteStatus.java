package com.beautica.review.invite.entity;

/**
 * Phase 399 D3 — lifecycle of a {@link ReviewInvite}. Mirrors {@code chk_review_invites_status} (V213).
 *
 * <ul>
 *   <li>{@link #SCHEDULED} — created on visit completion, waiting for {@code sendAfter} (phase 400).</li>
 *   <li>{@link #SENT} — SMS sent; {@code tokenHash}, {@code sentAt}, {@code expiresAt} set.</li>
 *   <li>{@link #CONSUMED} — the guest submitted their review(s) (phase 401); terminal.</li>
 *   <li>{@link #EXPIRED} — {@code expiresAt} passed unused; terminal.</li>
 *   <li>{@link #SKIPPED} — an eligibility gate failed; {@code skipReason} set; terminal.</li>
 *   <li>{@link #FAILED} — SMS delivery failed after the retry budget; terminal.</li>
 * </ul>
 */
public enum ReviewInviteStatus {
    SCHEDULED,
    SENT,
    CONSUMED,
    EXPIRED,
    SKIPPED,
    FAILED
}
