-- Phase 11.1 — Password reset tokens.
-- Dedicated side table (NOT inline on users) mirroring invite_tokens: a reset
-- token is a transient single-use credential with its own lifecycle, and the
-- users table already carries the email-verification OTP columns. The raw token
-- is emailed once; only its SHA-256 hash is stored here, so a DB leak yields no
-- usable reset links.
--
-- Design decisions:
--   • token VARCHAR(512) UNIQUE — SHA-256 hex output is 64 chars but 512 gives
--     headroom if the hash algorithm ever changes; mirrors invite_tokens.token.
--   • is_used BOOLEAN NOT NULL DEFAULT FALSE — single-use, flipped on consume.
--   • expires_at TIMESTAMPTZ NOT NULL — TTL enforced in the application layer
--     (app.password-reset.token-expiration-hours, default 1 h).
--   • user_id FK with ON DELETE CASCADE — orphan rows are removed automatically
--     when an account is deleted. No orphan blob risk (no R2 reference here).
--   • idx_password_reset_tokens_user_id — supports the "mark all used for user"
--     UPDATE sweep on successful reset (Phase 11.3) and eventual stale-row cleanup.

CREATE TABLE password_reset_tokens (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    token       VARCHAR(512) NOT NULL UNIQUE,
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    expires_at  TIMESTAMPTZ NOT NULL,
    is_used     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Lookup is always by hashed token (the UNIQUE constraint already provides a
-- B-tree index on token). The user_id index is for the per-user bulk UPDATE
-- that invalidates all outstanding reset links after a successful reset.
CREATE INDEX idx_password_reset_tokens_user_id ON password_reset_tokens (user_id);
