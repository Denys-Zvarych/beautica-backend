-- non-destructive
-- Reason: one new empty table; no existing table is altered or locked beyond the FK reference check.
--
-- V245 — Phase B516 (AUTH-50, CLI-50, NTF-56): optional notifications email for every account.
--
-- user_pending_emails
--   An address the user asked to add / change to, waiting for its emailed 6-digit code (AUTH-03 rules: 15 min,
--   5 attempts per code, 60 s cooldown, cumulative lockout surviving a resend). users.email is written only at a
--   successful verify (NotificationEmailService), so business mail can never reach an unconfirmed address (NTF-56).
--   At most one row per account (user_id is the key); the row is deleted on success. Only the peppered SHA-256 hex
--   of the code is stored. ON DELETE CASCADE: the pending address goes with the account (no external storage).
--   email is stored lower-cased and trimmed (the service normalises); the CHECKs pin that shape and the hash format.
--
-- Immutable-migration rule (Anti-Bug §O-9): new version, max existing = V244.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '1min';

CREATE TABLE user_pending_emails (
    user_id       UUID         PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    email         VARCHAR(255) NOT NULL,
    code_hash     VARCHAR(64)  NOT NULL,
    sent_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ  NOT NULL,
    attempts      SMALLINT     NOT NULL DEFAULT 0,
    failed_total  SMALLINT     NOT NULL DEFAULT 0,
    locked_until  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_user_pending_emails_email_normalised
        CHECK (email = lower(btrim(email)) AND position('@' IN email) > 1),
    CONSTRAINT ck_user_pending_emails_code_hash CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_user_pending_emails_attempts CHECK (attempts >= 0 AND failed_total >= 0),
    CONSTRAINT ck_user_pending_emails_expiry CHECK (expires_at > sent_at)
);
