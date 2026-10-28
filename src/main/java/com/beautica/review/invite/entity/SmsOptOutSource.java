package com.beautica.review.invite.entity;

/**
 * Phase 399 D4 — where an {@link SmsOptOut} came from. Mirrors {@code chk_sms_opt_outs_source} (V213).
 * {@link #REVIEW_INVITE} = the opt-out link on the {@code /r/{token}} page (phase 401 D6).
 */
public enum SmsOptOutSource {
    REVIEW_INVITE
}
