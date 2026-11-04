package com.beautica.review.invite.entity;

/**
 * Why a review invite ended {@link ReviewInviteStatus#SKIPPED} — one value per eligibility gate of phase 400 D2 / D3 and
 * phase 402 D3. Mirrors {@code chk_review_invites_skip_reason_value} (V213, replaced by V216, V228, then V229); a new value needs a
 * migration that replaces that CHECK by name in the same PR.
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
    STALE,
    /**
     * 400 audit cycle 1 — the send job could not map the row's visit data (unknown {@code booking_source}, a NULL
     * flag). Skipping that one row keeps it from failing the batch and being re-claimed every tick (V216).
     */
    INVALID_DATA,
    /**
     * Phase 434 audit-fix (sec M2) — STAFF walk-in only: the walk-in phone is the phone of an account of the provider
     * being rated (the performing master; for a salon booking also the owner, admins and salon masters). Blocks
     * self-review farming (V228; salon colleagues added by the LOW-1 fix).
     */
    PROVIDER_OWN_PHONE,
    /**
     * Phase 434 audit-fix (sec M1) — STAFF walk-in only: the same phone already got a non-skipped invite for the same
     * provider (performing master, or the salon of a salon booking) within
     * {@code app.booking.review-invite.staff-phone-window}. Caps provider-driven SMS to one per phone per window (V228).
     */
    REPEAT_PHONE,
    /**
     * Phase 434 audit-fix (sec LOW-4) — STAFF walk-in only: the phone already has
     * {@code app.booking.review-invite.staff-phone-global-cap} non-skipped STAFF invites across all providers within
     * {@code staff-phone-window} (V229).
     */
    PHONE_GLOBAL_CAP,
    /**
     * Phase 434 audit-fix (sec LOW-4) — STAFF walk-in only: the provider (master, or salon of a salon booking) already
     * created {@code app.booking.review-invite.staff-provider-daily-cap} non-skipped STAFF invites in the last 24 h
     * (V229).
     */
    PROVIDER_DAILY_CAP
}
