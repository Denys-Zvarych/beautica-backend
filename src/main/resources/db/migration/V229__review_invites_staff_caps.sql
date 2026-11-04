-- non-destructive
-- Reason: the replacement chk_review_invites_skip_reason_value only ADDS PHONE_GLOBAL_CAP and PROVIDER_DAILY_CAP, so it
-- is strictly looser than V228's and every existing row (and every previous-app write) still satisfies it; the new
-- index is additive.
-- Phase 434 audit-fix (sec LOW-4) — STAFF walk-in review invite caps:
--   * PHONE_GLOBAL_CAP: a STAFF invite is SKIPPED when the same phone_hash already has
--     app.booking.review-invite.staff-phone-global-cap (default 3) non-SKIPPED STAFF invites across ALL providers
--     within staff-phone-window — ReviewInviteRepository#countPriorStaffInvitesForPhone (served by V228's
--     idx_review_invites_phone_created).
--   * PROVIDER_DAILY_CAP: a STAFF invite is SKIPPED when its provider (master, or salon of a salon booking) already
--     created app.booking.review-invite.staff-provider-daily-cap (default 50) non-SKIPPED STAFF invites in the last
--     24 h — ReviewInviteRepository#countRecentStaffInvitesForProvider.
-- idx_review_invites_created_unskipped mirrors that per-provider lookup's predicate exactly
-- (status <> 'SKIPPED' AND created_at in [anchor - 24h, anchor)) — §E-5 / §O-6.
-- V228 is applied locally and immutable; fixed forward. The CHECK is replaced by name.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

CREATE INDEX idx_review_invites_created_unskipped ON review_invites (created_at) WHERE status <> 'SKIPPED';

ALTER TABLE review_invites DROP CONSTRAINT chk_review_invites_skip_reason_value;

-- Mirrors enum ReviewInviteSkipReason (phase 400 D2/D3 + audit cycle 1, 402 D3, 434 audit-fix). A new reason replaces this CHECK by name.
ALTER TABLE review_invites ADD CONSTRAINT chk_review_invites_skip_reason_value CHECK (
    skip_reason IS NULL OR skip_reason IN ('NO_PHONE', 'ANONYMISED', 'SMS_DISABLED', 'INVITES_DISABLED',
                                           'OPTED_OUT', 'NOTHING_TO_REVIEW', 'POLICY_VERSION', 'STALE',
                                           'INVALID_DATA', 'PROVIDER_OWN_PHONE', 'REPEAT_PHONE',
                                           'PHONE_GLOBAL_CAP', 'PROVIDER_DAILY_CAP')
);
