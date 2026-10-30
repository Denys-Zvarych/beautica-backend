-- non-destructive
-- Reason: review_invites belongs to the unreleased phase 399/400 track, and every write path already clears token_hash, sent_at and expires_at on any status that is not SENT/CONSUMED/EXPIRED, so every existing row satisfies the new CHECK.
-- Phase 400 audit-fix (cycle 2) on top of V213/V215/V216 (already applied on local dev DBs, so a new version).
--
-- chk_review_invites_unsent_no_token: an invite that is not (or no longer) sent carries no token material.
-- V213's chk_review_invites_sent_fields covers one direction only (SENT/CONSUMED/EXPIRED => token set). This is the
-- converse: SCHEDULED / SKIPPED / FAILED => token_hash, sent_at and expires_at are all NULL. It makes a
-- redeemable-looking hash on a dead row (e.g. a FAILED invite whose SMS may have arrived) impossible at the DB
-- level, so the phase 401 redemption lookup can never match a voided token even if a future code path forgets
-- ReviewInvite.voidToken(). Every current write path already satisfies it:
--   * INSERT (insertIfAbsent / insertForBookingIfAbsent): SCHEDULED or SKIPPED, no token columns written;
--   * ReviewInvite.schedule / skippedAtCreate*: token fields never set;
--   * skip(...) (gates, STALE, INVALID_DATA): only from SCHEDULED, which carries no token;
--   * recordFailedSend: clears all three before going SCHEDULED (retry) or FAILED (attempts spent);
--   * recordAmbiguousSend: voidToken() before FAILED;
--   * releaseUnsent: voidToken() before SCHEDULED;
--   * expireSent: SENT -> EXPIRED, which stays inside the token-bearing set.
-- status is NOT NULL, so the predicate never passes vacuously on the status side.
ALTER TABLE review_invites ADD CONSTRAINT chk_review_invites_unsent_no_token CHECK (
    status IN ('SENT', 'CONSUMED', 'EXPIRED')
    OR (token_hash IS NULL AND sent_at IS NULL AND expires_at IS NULL)
);
