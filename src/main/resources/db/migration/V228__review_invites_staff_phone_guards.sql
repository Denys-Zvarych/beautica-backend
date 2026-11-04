-- non-destructive
-- Reason: the replacement chk_review_invites_skip_reason_value only ADDS PROVIDER_OWN_PHONE and REPEAT_PHONE, so it is strictly looser than V216's and every existing row (and every previous-app write) still satisfies it; the new index is additive.
-- Phase 434 audit-fix (sec M1/M2) — STAFF walk-in review invites:
--   * REPEAT_PHONE: a STAFF invite is SKIPPED when the same phone_hash already has a non-SKIPPED invite for the same
--     provider (performing master, or the salon of a salon booking) created within
--     app.booking.review-invite.staff-phone-window (default 30d) — ReviewInviteRepository#existsPriorInviteForPhoneAndProvider.
--   * PROVIDER_OWN_PHONE: a STAFF invite is SKIPPED when the walk-in phone is the provider's own account phone.
-- idx_review_invites_phone_created mirrors the repeat lookup's predicate exactly
-- (phone_hash = ? AND status <> 'SKIPPED' AND created_at in [anchor - window, anchor)) — §E-5 / §O-6.
-- V216 is applied and immutable; fixed forward. The CHECK is replaced by name.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

CREATE INDEX idx_review_invites_phone_created ON review_invites (phone_hash, created_at) WHERE status <> 'SKIPPED';

ALTER TABLE review_invites DROP CONSTRAINT chk_review_invites_skip_reason_value;

-- Mirrors enum ReviewInviteSkipReason (phase 400 D2/D3 + audit cycle 1, 402 D3, 434 audit-fix). A new reason replaces this CHECK by name.
ALTER TABLE review_invites ADD CONSTRAINT chk_review_invites_skip_reason_value CHECK (
    skip_reason IS NULL OR skip_reason IN ('NO_PHONE', 'ANONYMISED', 'SMS_DISABLED', 'INVITES_DISABLED',
                                           'OPTED_OUT', 'NOTHING_TO_REVIEW', 'POLICY_VERSION', 'STALE',
                                           'INVALID_DATA', 'PROVIDER_OWN_PHONE', 'REPEAT_PHONE')
);
