-- Harden email verification schema (phase-1.1 security hardening).
-- Renames verification_code to verification_code_hash so the application
-- stores a keyed HMAC-SHA256 hex digest (64 lowercase hex chars), keyed with
-- a server-side pepper (APP_OTP_PEPPER), instead of the raw 6-digit OTP.
-- (Originally this comment claimed HMAC-SHA256 while the code used a bare
-- unkeyed SHA-256; the keyed HMAC was introduced alongside V50 — the digest
-- length and lowercase-hex alphabet are unchanged, so the CHECK below still
-- holds.)
-- Adds verification_attempts to enforce a 5-attempt per-OTP lock-out.
-- CHECK constraint enforces the hash format at write time.

ALTER TABLE users
    RENAME COLUMN verification_code TO verification_code_hash;

ALTER TABLE users
    ALTER COLUMN verification_code_hash TYPE VARCHAR(64);

ALTER TABLE users
    ADD COLUMN verification_attempts SMALLINT NOT NULL DEFAULT 0;

ALTER TABLE users
    ADD CONSTRAINT chk_verification_code_hash_format
        CHECK (verification_code_hash ~ '^[a-f0-9]{64}$' OR verification_code_hash IS NULL);
