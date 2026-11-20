-- contract-of: V1
-- Why contract-of V1: this file reshapes V1's invite_tokens; the flagged step is the FK of the NEW nullable column
-- invited_by (ADD COLUMN invited_by UUID REFERENCES users (id) ON DELETE SET NULL). Every existing row gets NULL (the
-- FK holds trivially, nothing to backfill or validate) and the previous app version never reads or writes invited_by,
-- so a rollback to it on this schema is safe.
--
-- B520 (AUTH-16, AUTH-18, AUTH-52, SAL-48) — staff invites go to a Ukrainian mobile number by SMS; the email
-- invite path is retired in the same release.
--
-- invite_tokens
--   phone       the invited +380 mobile number (E.164). NULL on every row written before this migration (email
--               invites); those rows are kept as history and can no longer be validated or accepted.
--   email       NULL for every new (phone) invite; kept on old rows (history of accepted email invites).
--   invited_by  the user who sent the invite — the per-inviter SMS cap (10 per hour) counts by it. ON DELETE SET
--               NULL: a deleted inviter must never block DELETE FROM users (AUTH-20 rule), and an anonymous row
--               still counts toward the salon cap.
--   ck_invite_tokens_one_recipient  exactly one of email / phone (holds on every existing row: email NOT NULL until
--               now, phone new and NULL).
--   ux_invite_tokens_active_phone   at most one ACTIVE invite per (salon, phone) — the same predicate as
--               ux_invite_tokens_active (V153). The INSERT-time race guard of the phone path, and it also serves the
--               idempotency lookup `phone = ? AND salon_id = ? AND is_used = false AND revoked_at IS NULL`.
--   idx_invite_tokens_invited_by_created  serves the per-inviter cap count (invited_by = ? AND created_at > ?) and
--               the FK's ON DELETE SET NULL lookup. The per-salon cap count is served by
--               idx_invite_tokens_salon_created (V153).
--
--   idx_invite_tokens_email_active (V153) is DROPPED: its only reader, the email dispatch-idempotency lookup, is
--               removed with the email invite path, and as a partial index on (email, salon_id) it would otherwise
--               index every new phone row (email NULL) for nothing. ux_invite_tokens_active (lower(email), salon_id)
--               stays as the integrity backstop of the email rows still in the table.
--
-- The 30-day retention sweep (InviteTokenCleanupJob, D5) scans phone rows only; the table holds a handful of rows
-- per salon, so it gets no index of its own.
--
-- Locking: one transaction; ACCESS EXCLUSIVE on invite_tokens (small: one row per invite ever sent) until COMMIT.
-- Immutable-migration rule (Anti-Bug §O-9): new version, max existing = V250.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE invite_tokens
    ADD COLUMN phone VARCHAR(20),
    ADD COLUMN invited_by UUID REFERENCES users (id) ON DELETE SET NULL;

ALTER TABLE invite_tokens
    ALTER COLUMN email DROP NOT NULL;

-- A CHECK passes on NULL: the format check only constrains a present phone; the pair check makes one of them present.
ALTER TABLE invite_tokens
    ADD CONSTRAINT ck_invite_tokens_phone_format
    CHECK (phone IS NULL OR phone ~ '^\+380[0-9]{9}$');

ALTER TABLE invite_tokens
    ADD CONSTRAINT ck_invite_tokens_one_recipient
    CHECK (num_nonnulls(email, phone) = 1);

CREATE UNIQUE INDEX ux_invite_tokens_active_phone
    ON invite_tokens (salon_id, phone)
    WHERE is_used = false AND revoked_at IS NULL;

CREATE INDEX idx_invite_tokens_invited_by_created
    ON invite_tokens (invited_by, created_at);

DROP INDEX IF EXISTS idx_invite_tokens_email_active;
