-- non-destructive
-- Reason: the replacement chk_review_invites_skip_reason_value only ADDS the value INVALID_DATA, so it is strictly looser than V213's and every existing row (and every previous-app write) still satisfies it.
-- Phase 400 audit-fix (cycle 1) on top of V213/V215 (already applied on local dev DBs, so a new version).
--
-- 1. Partial indexes that mirror the send job's predicates exactly (§E-5 / §O-6):
--    * claimDue:   WHERE status = 'SCHEDULED' AND send_after <= :now ORDER BY send_after
--                  → idx_review_invites_scheduled_send_after (send_after) WHERE status = 'SCHEDULED'.
--      It replaces the full idx_review_invites_status_send_after (status, send_after), whose only reader was this
--      claim: every terminal row (SKIPPED / CONSUMED / EXPIRED / FAILED — the bulk of the table) paid an index
--      write and index space for a predicate that never selects it.
--    * expireSent: WHERE status = 'SENT' AND expires_at <= :now ORDER BY expires_at
--                  → idx_review_invites_sent_expires (expires_at) WHERE status = 'SENT'. The old composite led on
--      status but ordered by send_after, so the sweep had to filter and sort every SENT row by expires_at.
-- 2. ReviewInviteSkipReason.INVALID_DATA: the send job could not map the row's visit data (an unknown
--    booking_source, a NULL flag) — that one invite is skipped with this reason instead of failing the whole batch
--    transaction and re-claiming (and re-failing) the same rows every tick. The CHECK is replaced by name.
CREATE INDEX idx_review_invites_scheduled_send_after ON review_invites (send_after) WHERE status = 'SCHEDULED';

CREATE INDEX idx_review_invites_sent_expires ON review_invites (expires_at) WHERE status = 'SENT';

DROP INDEX idx_review_invites_status_send_after;

ALTER TABLE review_invites DROP CONSTRAINT chk_review_invites_skip_reason_value;

-- Mirrors enum ReviewInviteSkipReason (phase 400 D2/D3 + audit cycle 1, 402 D3). A new reason replaces this CHECK by name.
ALTER TABLE review_invites ADD CONSTRAINT chk_review_invites_skip_reason_value CHECK (
    skip_reason IS NULL OR skip_reason IN ('NO_PHONE', 'ANONYMISED', 'SMS_DISABLED', 'INVITES_DISABLED',
                                           'OPTED_OUT', 'NOTHING_TO_REVIEW', 'POLICY_VERSION', 'STALE',
                                           'INVALID_DATA')
);
