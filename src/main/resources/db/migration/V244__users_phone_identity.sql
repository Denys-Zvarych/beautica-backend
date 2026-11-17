-- non-destructive
-- Reason: one nullable column added, one NOT NULL dropped (email), one partial unique index over an empty predicate
-- set, two CHECKs that hold on every existing row, one nullable FK column, one defaulted column and one new table.
--
-- V244 — Phase B514 (phone-first identity, AUTH-D08..D15): phone registration + login by phone or email.
--
-- users
--   * phone_verified_at TIMESTAMPTZ NULL — stamped when the account proved its phone by SMS code (registration
--     ticket, B514; add/change phone, B516). NULL on every existing row: legacy accounts stay unverified (AUTH-D12).
--   * email DROP NOT NULL — email is optional for every role (AUTH-D11). The UNIQUE constraint stays; Postgres
--     treats NULLs as distinct, so any number of phone-only accounts may coexist.
--   * ux_users_verified_phone — a verified phone is unique among verified accounts (AUTH-42). Partial: unverified
--     legacy phone_number values (free text, possibly duplicated) are not constrained. Backs the login-by-phone
--     lookup (UserRepository#findByVerifiedPhone) whose predicate is exactly this one.
--   * ck_users_verified_phone_format — a verified phone is always E.164 +380 (AUTH-D15); the login lookup and the
--     unique index compare normalised strings only.
--   * ck_users_login_identity — every account keeps at least one login identifier (an email or a verified phone).
--     Holds on every existing row (email was NOT NULL until this file).
--
-- phone_otps (REUSE-FIRST, D1 — no second OTP table)
--   * purpose — which flow a code belongs to; a code verifies only for its own purpose. DEFAULT 'GUEST_BOOKING'
--     keeps every existing row and the pre-B514 insert path valid. PHONE_ADD / PHONE_CHANGE / PASSWORD_RESET are
--     reserved for B516 (same PR) so that phase needs no CHECK rewrite.
--   * user_id — the signed-in owner of a PHONE_ADD / PHONE_CHANGE code (B516). NULL for anonymous purposes.
--     ON DELETE CASCADE: an OTP of a deleted account is worthless. Indexed (every FK gets an index).
--
-- phone_registration_tickets (D2 — the AUTH-13 reset-ticket pattern keyed by phone instead of user)
--   Issued by POST /auth/register/phone/otp/verify, consumed once by register / invite accept. Only the SHA-256 hex
--   of the raw ticket is stored. No FK, no external storage: rows are swept by RegistrationTicketCleanupJob.
--
-- Locking (as V232): one transaction; ACCESS EXCLUSIVE on users until COMMIT. users is small (one row per
-- account); the index build and CHECK validation scan it once. lock_timeout bounds the wait to acquire the lock,
-- statement_timeout bounds each statement. SET LOCAL never leaks to the pooled connection.
-- Immutable-migration rule (Anti-Bug §O-9): new version, max existing = V243.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE users
    ADD COLUMN phone_verified_at TIMESTAMPTZ;

ALTER TABLE users
    ALTER COLUMN email DROP NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_users_verified_phone
    ON users (phone_number)
    WHERE phone_verified_at IS NOT NULL;

-- A CHECK passes on NULL: phone_number IS NOT NULL is spelled out so a verified row can never carry a NULL phone.
ALTER TABLE users
    ADD CONSTRAINT ck_users_verified_phone_format
    CHECK (phone_verified_at IS NULL
           OR (phone_number IS NOT NULL AND phone_number ~ '^\+380[0-9]{9}$'));

ALTER TABLE users
    ADD CONSTRAINT ck_users_login_identity
    CHECK (email IS NOT NULL OR phone_verified_at IS NOT NULL);

ALTER TABLE phone_otps
    ADD COLUMN purpose VARCHAR(32) NOT NULL DEFAULT 'GUEST_BOOKING';

ALTER TABLE phone_otps
    ADD CONSTRAINT ck_phone_otps_purpose
    CHECK (purpose IN ('GUEST_BOOKING', 'REGISTRATION', 'PHONE_ADD', 'PHONE_CHANGE', 'PASSWORD_RESET'));

ALTER TABLE phone_otps
    ADD COLUMN user_id UUID REFERENCES users (id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_phone_otps_user_id
    ON phone_otps (user_id)
    WHERE user_id IS NOT NULL;

CREATE TABLE phone_registration_tickets (
    id           UUID         PRIMARY KEY,
    ticket_hash  VARCHAR(64)  NOT NULL,
    phone        VARCHAR(20)  NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    used         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_phone_registration_tickets_hash UNIQUE (ticket_hash),
    CONSTRAINT ck_phone_registration_tickets_hash CHECK (ticket_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_phone_registration_tickets_phone CHECK (phone ~ '^\+380[0-9]{9}$')
);

-- Backs the cleanup sweep (DELETE WHERE expires_at < cutoff).
CREATE INDEX IF NOT EXISTS idx_phone_registration_tickets_expires_at
    ON phone_registration_tickets (expires_at);
