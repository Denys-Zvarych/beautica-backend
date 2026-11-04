-- non-destructive
-- Reason: both CHECKs constrain cancel_token_sealed, added NULL in this file, so no existing row passes a non-NULL value; a previous-app write that nulls cancel_token_hash still satisfies the pair CHECK because the trigger below nulls the sealed copy in the same row first. The CHECKs are added NOT VALID (no scan under ACCESS EXCLUSIVE) and validated by V231.
-- Phase 435 (user decision 2026-11-04) — reversible copy of the guest one-time cancel token.
--
-- V206 keeps only the SHA-256 of the token (cancel_token_hash), so the guest's own page
-- {FRONTEND_BASE_URL}/book/cancel/{token} cannot be rebuilt after create and every later guest SMS
-- (provider cancel, reschedule, 24h/2h reminders) had to drop its link. cancel_token_sealed holds the token
-- sealed with AES-256-GCM (GuestCancelTokenCipher, key GUEST_CANCEL_TOKEN_KEY, fresh 12-byte IV per row):
--   v2:<kid>:<base64(IV || ciphertext || tag)>   (100 chars for a UUID token; AAD = "<table>:<id>")
-- The hash stays the ONLY lookup key; the sealed copy is never queried.
--
-- Existing rows get NULL (no backfill: the raw token is gone) and simply produce no link.
--
-- Lifetime: the sealed copy never outlives the hash. chk_*_cancel_token_sealed_pair enforces it, and
-- trg_*_null_sealed_cancel_token nulls the sealed copy in any UPDATE that nulls the hash — consume
-- (guest self-cancel), visit-item cancel, retention anonymisation, the internal erase — including writes
-- from a previous app image that does not know this column (rollback safety). The application also nulls it
-- explicitly in each of those statements.
SET LOCAL lock_timeout = '3s';

ALTER TABLE bookings     ADD COLUMN cancel_token_sealed VARCHAR(128) NULL;
ALTER TABLE appointments ADD COLUMN cancel_token_sealed VARCHAR(128) NULL;

COMMENT ON COLUMN bookings.cancel_token_sealed IS
    'V230 (phase 435). AES-256-GCM sealed guest cancel token (GuestCancelTokenCipher, v2:<kid>: prefix). Set on a '
    'standalone LINK booking only; never a lookup key. NULL whenever cancel_token_hash is NULL.';
COMMENT ON COLUMN appointments.cancel_token_sealed IS
    'V230 (phase 435). Sealed visit cancel token (LINK visit / STAFF walk-in header). NULL whenever '
    'cancel_token_hash is NULL.';

-- Format (Anti-Bug §O-2) + pair rule. Added NOT VALID: a validated ADD CONSTRAINT CHECK scans the whole table while
-- holding ACCESS EXCLUSIVE (every read and write on bookings/appointments blocked for the scan). NOT VALID enforces
-- the CHECK on every new/updated row at once and skips the scan; V231 then runs VALIDATE CONSTRAINT in its own
-- transaction under SHARE UPDATE EXCLUSIVE, which does not block reads or writes.
-- Format (phase 435 audit-fix): v2:<kid>:<base64> only — 8-hex key id, AES-GCM bound to the row ("<table>:<id>").
-- The unbound v1: form is rejected (no deployed build ever wrote it).
ALTER TABLE bookings
    ADD CONSTRAINT chk_bookings_cancel_token_sealed_format
        CHECK (cancel_token_sealed ~ '^v2:[0-9a-f]{8}:[A-Za-z0-9+/]+={0,2}$') NOT VALID,
    ADD CONSTRAINT chk_bookings_cancel_token_sealed_pair
        CHECK (cancel_token_sealed IS NULL OR cancel_token_hash IS NOT NULL) NOT VALID;
ALTER TABLE appointments
    ADD CONSTRAINT chk_appointments_cancel_token_sealed_format
        CHECK (cancel_token_sealed ~ '^v2:[0-9a-f]{8}:[A-Za-z0-9+/]+={0,2}$') NOT VALID,
    ADD CONSTRAINT chk_appointments_cancel_token_sealed_pair
        CHECK (cancel_token_sealed IS NULL OR cancel_token_hash IS NOT NULL) NOT VALID;

CREATE OR REPLACE FUNCTION null_sealed_cancel_token() RETURNS trigger AS $$
BEGIN
    IF NEW.cancel_token_hash IS NULL THEN
        NEW.cancel_token_sealed := NULL;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql
   SET search_path = pg_catalog, pg_temp;

CREATE TRIGGER trg_bookings_null_sealed_cancel_token
    BEFORE UPDATE OF cancel_token_hash ON bookings
    FOR EACH ROW EXECUTE FUNCTION null_sealed_cancel_token();

CREATE TRIGGER trg_appointments_null_sealed_cancel_token
    BEFORE UPDATE OF cancel_token_hash ON appointments
    FOR EACH ROW EXECUTE FUNCTION null_sealed_cancel_token();
