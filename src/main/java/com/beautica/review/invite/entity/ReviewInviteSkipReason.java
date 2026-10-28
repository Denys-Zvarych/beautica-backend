package com.beautica.review.invite.entity;

/**
 * Why a review invite ended {@link ReviewInviteStatus#SKIPPED} — one value per eligibility gate of phase 400 D2 / D3 and
 * phase 402 D3. Mirrors V213 {@code chk_review_invites_skip_reason}; a new value needs a migration that replaces that
 * CHECK by name in the same PR.
 */
public enum ReviewInviteSkipReason {
    /** 400 D2 — the booking holds no guest phone. */
    NO_PHONE,
    /** 400 D2 / 402 D3 — the guest PII was anonymised before the send. */
    ANONYMISED,
    /** 400 D2 — {@code app.booking.sms.enabled} is off. */
    SMS_DISABLED,
    /** 400 D2 — {@code app.booking.review-invite.enabled} is off. */
    INVITES_DISABLED,
    /** 400 D2 — the phone hash is in {@code sms_opt_outs}. */
    OPTED_OUT,
    /** 400 D2 — no COMPLETED booking in the visit is left without a review. */
    NOTHING_TO_REVIEW,
    /** 400 D2 — the guest accepted a privacy-policy version older than {@code min-policy-version}. */
    POLICY_VERSION,
    /** 400 D3 — still unsent 72 h after {@code send_after}. */
    STALE
}
