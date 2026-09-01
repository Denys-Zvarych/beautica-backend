-- invite HISTORY on GET /salons/{salonId}/invites.
--
-- WHY THESE COLUMNS: cancelInvite (SalonService#cancelInvite) and acceptInvite
-- (InviteService#acceptInvite) both write the SAME flag is_used = true, so ACCEPTED and CANCELLED
-- are indistinguishable in the current schema. revoked_reason is the one bit time cannot tell you;
-- PENDING/EXPIRED stay DERIVED from expires_at + the injected Clock, so no cleanup job is needed
-- (there is deliberately none for invite_tokens — see the InviteToken entity Javadoc).
--
-- WHY THE INDEX SWAP IS SAFE: both columns are added in THIS migration, so every pre-existing
-- row has revoked_at IS NULL. The new predicate (is_used = false AND revoked_at IS NULL)
-- therefore selects EXACTLY the row set the V101 predicate (is_used = false) selected, over
-- which the old UNIQUE index already held. The rebuild cannot fail on historical duplicates.
-- No backfill, no NOT VALID/VALIDATE split.
--
-- Plain CREATE / DROP (no CONCURRENTLY): pre-launch table is tiny and Flyway runs each
-- migration in its own transaction (same rationale as V101).

ALTER TABLE invite_tokens
    ADD COLUMN revoked_at     TIMESTAMP WITH TIME ZONE,
    ADD COLUMN revoked_reason VARCHAR(16);

ALTER TABLE invite_tokens
    ADD CONSTRAINT ck_invite_tokens_revoked_pair
        CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL)),
    ADD CONSTRAINT ck_invite_tokens_revoked_reason
        CHECK (revoked_reason IS NULL OR revoked_reason IN ('CANCELLED', 'SUPERSEDED'));

-- V101's guard, re-scoped: a SUPERSEDED row keeps is_used = false (it was never accepted) and
-- must NOT hold the active slot, otherwise re-inviting the same address collides.
DROP INDEX ux_invite_tokens_active;
CREATE UNIQUE INDEX ux_invite_tokens_active
    ON invite_tokens (lower(email), salon_id)
    WHERE is_used = false AND revoked_at IS NULL;

-- V149's index backed the retired pending-only finder
-- (findBySalonIdAndIsUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc), which this phase deletes.
-- The history query is `salon_id = ? ORDER BY created_at DESC, id DESC LIMIT 200` — a partial
-- is_used = false index can never serve it. Replace, do not accumulate.
DROP INDEX idx_invite_tokens_salon_pending;
CREATE INDEX idx_invite_tokens_salon_created
    ON invite_tokens (salon_id, created_at DESC, id DESC);

-- P2 — V16's idx_invite_tokens_email_used (email, is_used) is retired for a partial index whose
-- predicate matches the live-invite lookup exactly.
--
-- WHY NOW AND NOT BEFORE: until this migration, an expired invite displaced by a re-invite was
-- HARD-DELETED, so (email, is_used = false) stayed at most one row per salon forever. This
-- migration retains those rows as SUPERSEDED history, and a SUPERSEDED row keeps is_used = false —
-- so the old index now returns every past invite for a churned address and Postgres discards them
-- with `revoked_at IS NULL` as a residual filter. Measured on 50 re-invites of one address:
-- `Rows Removed by Filter: 50`, growing linearly with churn. The retention change is what created
-- the problem, so the index fix ships in the same migration that creates it.
--
-- WHY THIS SHAPE: the only query on this index is
--   email = ? AND salon_id = ? AND is_used = false AND revoked_at IS NULL
-- (InviteTokenRepository#findByEmailAndSalonIdAndIsUsedFalseAndRevokedAtIsNull, the invite-dispatch
-- idempotency check — the sole caller now that the unscoped findByEmailAndIsUsedFalse is deleted).
-- Moving both booleans into the index PREDICATE keeps only live invites in the index — a handful
-- of rows platform-wide, since a row leaves it the moment it is accepted, cancelled or superseded —
-- and promotes salon_id into the key so the whole predicate is index-covered with no residual
-- filter at all. is_used as a KEY column (V16's shape) could never do that: a two-value column
-- leading nothing is close to useless as a key, and it still left revoked_at unindexed.
--
-- NOT redundant with ux_invite_tokens_active, which has the identical predicate: that index is on
-- lower(email), and Postgres cannot use an expression index to answer a bare `email = ?`
-- comparison. It stays the INSERT-time uniqueness backstop; this one serves the lookup.
DROP INDEX idx_invite_tokens_email_used;
CREATE INDEX idx_invite_tokens_email_active
    ON invite_tokens (email, salon_id)
    WHERE is_used = false AND revoked_at IS NULL;
