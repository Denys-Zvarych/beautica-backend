-- Phase 1.x security hardening — resend-surviving cumulative brute-force bound.
--
-- Problem: resend-verification resets the per-OTP attempt counter to 0, so an
-- attacker could loop resend -> 5 guesses -> resend indefinitely against the
-- 1,000,000-value OTP space. The IP limiter is the only other bound and is
-- IP-rotatable.
--
-- Fix: a lifetime failure counter that resend does NOT reset, plus a lockout
-- timestamp. After a cumulative threshold of failed verifies the account is
-- locked for a fixed window; while locked both verify-email and
-- resend-verification reject with the existing generic failure shape (no new
-- enumeration oracle).

ALTER TABLE users
    ADD COLUMN verification_failed_total SMALLINT NOT NULL DEFAULT 0;

ALTER TABLE users
    ADD COLUMN verification_locked_until TIMESTAMPTZ;

ALTER TABLE users
    ADD CONSTRAINT chk_verification_failed_total_non_negative
        CHECK (verification_failed_total >= 0);

-- Partial index mirroring the scheduled stale-OTP sweep predicate
-- (email_verified = false AND verification_code_expires_at < now() - 24h).
-- Single-column indexes on either column would be wasted writes for this
-- always-conjoined filter (Anti-Bug Playbook E5 / O6).
CREATE INDEX idx_users_stale_unverified_otp
    ON users (verification_code_expires_at)
    WHERE email_verified = false;
