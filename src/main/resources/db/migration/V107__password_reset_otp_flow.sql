-- Phase A1 — replaces the email-link password-reset flow with a 6-digit OTP flow,
-- mirroring the existing email-verification OTP pattern (V48/V49/V50/V63).
--
-- Two changes:
--
-- 1) The old password_reset_tokens table (V55/V100) becomes password_reset_tickets: a
--    "ticket" is now minted ONLY after a 6-digit OTP has been verified (it is no longer
--    minted directly off a forgot-password request), so the old column name `token` is
--    renamed to `ticket_hash` to reflect that it authorizes the reset-CONFIRM step, not the
--    reset-REQUEST step. Same SHA-256-hex-at-rest contract as before — only the hash is
--    ever persisted, never the raw ticket. Constraint/index names are renamed alongside the
--    table so nothing under this table still says "tokens".
--
-- 2) users gains 5 columns mirroring verification_code_hash / verification_code_expires_at /
--    verification_attempts / verification_failed_total / verification_locked_until exactly
--    (same types, same CHECK-constraint shapes) so PasswordResetOtpProcessor can reuse the
--    EmailVerificationProcessor locked critical section verbatim (per-code attempt cap +
--    resend-surviving cumulative lockout).

ALTER TABLE password_reset_tokens RENAME TO password_reset_tickets;
ALTER TABLE password_reset_tickets RENAME COLUMN token TO ticket_hash;

ALTER TABLE password_reset_tickets RENAME CONSTRAINT password_reset_tokens_pkey
    TO password_reset_tickets_pkey;
ALTER TABLE password_reset_tickets RENAME CONSTRAINT password_reset_tokens_token_key
    TO password_reset_tickets_ticket_hash_key;
ALTER TABLE password_reset_tickets RENAME CONSTRAINT password_reset_tokens_user_id_fkey
    TO password_reset_tickets_user_id_fkey;

ALTER INDEX idx_password_reset_tokens_user_id RENAME TO idx_password_reset_tickets_user_id;
ALTER INDEX idx_password_reset_tokens_expires_at RENAME TO idx_password_reset_tickets_expires_at;

-- users: 6-digit OTP columns for the password-reset request/verify step. Same shapes as
-- verification_code_hash / verification_code_expires_at / verification_attempts /
-- verification_failed_total / verification_locked_until (V48/V49/V50/V63).
ALTER TABLE users
    ADD COLUMN password_reset_code_hash VARCHAR(64);

ALTER TABLE users
    ADD COLUMN password_reset_code_expires_at TIMESTAMPTZ;

ALTER TABLE users
    ADD COLUMN password_reset_attempts SMALLINT NOT NULL DEFAULT 0;

ALTER TABLE users
    ADD COLUMN password_reset_failed_total SMALLINT NOT NULL DEFAULT 0;

ALTER TABLE users
    ADD COLUMN password_reset_locked_until TIMESTAMPTZ;

ALTER TABLE users
    ADD CONSTRAINT chk_password_reset_code_hash_format
        CHECK (password_reset_code_hash ~ '^[a-f0-9]{64}$' OR password_reset_code_hash IS NULL);

ALTER TABLE users
    ADD CONSTRAINT chk_password_reset_attempts_range
        CHECK (password_reset_attempts >= 0 AND password_reset_attempts <= 10);

ALTER TABLE users
    ADD CONSTRAINT chk_password_reset_failed_total_non_negative
        CHECK (password_reset_failed_total >= 0);
