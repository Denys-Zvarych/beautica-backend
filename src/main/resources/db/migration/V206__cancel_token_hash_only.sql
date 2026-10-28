-- non-destructive
-- Reason: the CHECKs and unique indexes constrain cancel_token_hash, added NULL here; previous-image writes pass because the trigger below hashes them first.
-- Review-trust track, phase 1 — guest cancel tokens are stored as a SHA-256 hash only.
--
-- Why: bookings.cancel_token / appointments.cancel_token held the guest's one-time cancel CAPABILITY in
-- plaintext (whoever reads the row can cancel the visit). The raw token now exists only in the guest's
-- SMS / cancel link and in the create response that hands it to the guest. The database keeps
-- cancel_token_hash = lower-hex SHA-256 of the token's canonical text (UTF-8) — the application computes
-- it in CancelTokenHasher, this file computes the identical preimage with
--     encode(sha256(convert_to(cancel_token::text, 'UTF8')), 'hex')
-- (uuid::text is the same lower-case hyphenated form as java.util.UUID#toString). Every lookup and every
-- one-time consume hashes the incoming token, so the public API (GET/POST /api/v1/book/cancel/{token})
-- is unchanged and every link already sent keeps working: the backfill below hashes existing tokens.
--
-- Plaintext column: KEPT (always NULL), dropped by a later contract migration. Not dropped here because
-- the image running before this one (Railway rollback target) still maps and writes cancel_token:
--   * trg_*_hash_legacy_cancel_token turns any write of a plaintext token (previous image's INSERT,
--     raw-SQL fixtures, seed scripts) into its hash and clears the plaintext, before the CHECKs run, so
--     plaintext never reaches disk and those writes are still accepted.
--   * The previous image's anonymiser nulls cancel_token while setting guest_pii_anonymised_at; the
--     trigger then nulls the hash too, so the anonymised arm of the guest-fields CHECK still holds.
--   * Known rollback degradation: the previous image LOOKS UP by plaintext, which is now always NULL,
--     so while a rollback is live its guest cancel page answers 404 ("link no longer valid") — the
--     same response as an unknown token. Rolling forward restores every link (the hashes are intact).
--   * chk_*_cancel_token_plaintext_null makes "no plaintext at rest" a DB invariant.
-- The V90 / V124 unique indexes on the plaintext column now index nothing and go with the column.
--
-- Guest-fields CHECKs: V199's chk_bookings_guest_fields / chk_appointment_guest_fields byte-for-byte,
-- with cancel_token replaced by cancel_token_hash (the rule "an active LINK row carries a token; APP,
-- STAFF, DETACHED and anonymised rows carry none" now reads the column that holds the token).
--
-- Locking (as V199): one Flyway transaction; the ALTERs take ACCESS EXCLUSIVE on bookings and
-- appointments until COMMIT, through the backfill UPDATE, the CHECK scans and the unique-index builds.
-- lock_timeout bounds the wait to acquire; statement_timeout bounds the scans.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE bookings     ADD COLUMN cancel_token_hash VARCHAR(64) NULL;
ALTER TABLE appointments ADD COLUMN cancel_token_hash VARCHAR(64) NULL;

COMMENT ON COLUMN bookings.cancel_token_hash IS
    'V206. Lower-hex SHA-256 of the guest (LINK) one-time cancel token (CancelTokenHasher). The raw '
    'token is never stored. NULL once consumed / terminal-by-provider / anonymised, and on APP/STAFF rows.';
COMMENT ON COLUMN appointments.cancel_token_hash IS
    'V206. Mirror of bookings.cancel_token_hash for the guest visit header.';
COMMENT ON COLUMN bookings.cancel_token IS
    'Retired by V206 (plaintext cancel token). Always NULL; dropped by a later contract migration.';
COMMENT ON COLUMN appointments.cancel_token IS
    'Retired by V206 (plaintext cancel token). Always NULL; dropped by a later contract migration.';

-- The guest-fields CHECKs read cancel_token; they are re-added on cancel_token_hash after the backfill.
ALTER TABLE bookings     DROP CONSTRAINT chk_bookings_guest_fields;
ALTER TABLE appointments DROP CONSTRAINT chk_appointment_guest_fields;

-- Backfill: hash every live plaintext token (existing SMS links keep working), then clear the plaintext.
UPDATE bookings
   SET cancel_token_hash = encode(sha256(convert_to(cancel_token::text, 'UTF8')), 'hex'),
       cancel_token      = NULL
 WHERE cancel_token IS NOT NULL;

UPDATE appointments
   SET cancel_token_hash = encode(sha256(convert_to(cancel_token::text, 'UTF8')), 'hex'),
       cancel_token      = NULL
 WHERE cancel_token IS NOT NULL;

-- ── CHECKs: ONE ALTER TABLE per table so Postgres validates all three in a single scan ──────────────
-- chk_*_cancel_token_hash_format (Anti-Bug §O-2): exactly the lower-hex SHA-256 the application emits.
-- chk_*_cancel_token_plaintext_null: no plaintext token at rest, ever (the trigger below clears it
-- before this is evaluated).
-- chk_*_guest_fields: V199's rule with cancel_token -> cancel_token_hash.

-- ── bookings ─────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE bookings
    ADD CONSTRAINT chk_bookings_cancel_token_hash_format CHECK (cancel_token_hash ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT chk_bookings_cancel_token_plaintext_null CHECK (cancel_token IS NULL),
    ADD CONSTRAINT chk_bookings_guest_fields CHECK (
        (booking_source = 'LINK'
            AND client_detached_at IS NULL
            AND client_id IS NULL
            AND (
                (guest_pii_anonymised_at IS NOT NULL
                    AND guest_name IS NOT NULL AND btrim(guest_name) <> ''
                    AND guest_surname IS NULL
                    AND guest_phone IS NULL
                    AND cancel_token_hash IS NULL
                    AND client_comment IS NULL
                    AND client_cancellation_note IS NULL)
             OR (guest_pii_anonymised_at IS NULL
                    AND guest_name IS NOT NULL
                    AND guest_phone IS NOT NULL
                    AND (cancel_token_hash IS NOT NULL
                         OR status IN ('CANCELLED', 'COMPLETED', 'NOT_COMPLETED', 'DECLINED')))
            ))
     OR (booking_source = 'APP'
            AND client_detached_at IS NULL
            AND client_id IS NOT NULL
            AND guest_name IS NULL
            AND guest_surname IS NULL
            AND guest_phone IS NULL
            AND cancel_token_hash IS NULL)
     OR (booking_source = 'STAFF'
            AND client_detached_at IS NULL
            AND cancel_token_hash IS NULL
            AND (
                (client_id IS NOT NULL
                    AND guest_name IS NULL
                    AND guest_surname IS NULL
                    AND guest_phone IS NULL)
             OR (client_id IS NULL
                    AND guest_pii_anonymised_at IS NOT NULL
                    AND guest_name IS NOT NULL AND btrim(guest_name) <> ''
                    AND guest_surname IS NULL
                    AND guest_phone IS NULL
                    AND client_comment IS NULL
                    AND client_cancellation_note IS NULL)
             OR (client_id IS NULL
                    AND guest_pii_anonymised_at IS NULL
                    AND guest_name    IS NOT NULL AND btrim(guest_name)    <> ''
                    AND guest_surname IS NOT NULL AND btrim(guest_surname) <> ''
                    AND guest_phone IS NOT NULL)
            ))
     OR (client_detached_at IS NOT NULL
            AND client_id IS NULL
            AND guest_name IS NOT NULL AND btrim(guest_name) <> ''
            AND guest_surname IS NULL
            AND guest_phone IS NULL
            AND cancel_token_hash IS NULL)
    );

COMMENT ON CONSTRAINT chk_bookings_guest_fields ON bookings IS
    'V206: V199''s rule byte-for-byte with cancel_token replaced by cancel_token_hash (the plaintext '
    'token is no longer stored). ANONYMISED / LIVE split of the LINK arm and STAFF walk-in branch as V199.';

-- ── appointments ─────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE appointments
    ADD CONSTRAINT chk_appointments_cancel_token_hash_format CHECK (cancel_token_hash ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT chk_appointments_cancel_token_plaintext_null CHECK (cancel_token IS NULL),
    ADD CONSTRAINT chk_appointment_guest_fields CHECK (
        (booking_source = 'LINK'
            AND client_detached_at IS NULL
            AND client_id IS NULL
            AND (
                (guest_pii_anonymised_at IS NOT NULL
                    AND guest_name IS NOT NULL AND btrim(guest_name) <> ''
                    AND guest_surname IS NULL
                    AND guest_phone IS NULL
                    AND cancel_token_hash IS NULL
                    AND client_comment IS NULL
                    AND client_cancellation_note IS NULL)
             OR (guest_pii_anonymised_at IS NULL
                    AND guest_name IS NOT NULL
                    AND guest_phone IS NOT NULL
                    AND (cancel_token_hash IS NOT NULL
                         OR status IN ('CANCELLED', 'COMPLETED', 'NOT_COMPLETED', 'DECLINED')))
            ))
     OR (booking_source = 'APP'
            AND client_detached_at IS NULL
            AND client_id IS NOT NULL
            AND guest_name IS NULL
            AND guest_surname IS NULL
            AND guest_phone IS NULL
            AND cancel_token_hash IS NULL)
     OR (booking_source = 'STAFF'
            AND client_detached_at IS NULL
            AND cancel_token_hash IS NULL
            AND (
                (client_id IS NOT NULL
                    AND guest_name IS NULL
                    AND guest_surname IS NULL
                    AND guest_phone IS NULL)
             OR (client_id IS NULL
                    AND guest_pii_anonymised_at IS NOT NULL
                    AND guest_name IS NOT NULL AND btrim(guest_name) <> ''
                    AND guest_surname IS NULL
                    AND guest_phone IS NULL
                    AND client_comment IS NULL
                    AND client_cancellation_note IS NULL)
             OR (client_id IS NULL
                    AND guest_pii_anonymised_at IS NULL
                    AND guest_name    IS NOT NULL AND btrim(guest_name)    <> ''
                    AND guest_surname IS NOT NULL AND btrim(guest_surname) <> ''
                    AND guest_phone IS NOT NULL)
            ))
     OR (client_detached_at IS NOT NULL
            AND client_id IS NULL
            AND guest_name IS NOT NULL AND btrim(guest_name) <> ''
            AND guest_surname IS NULL
            AND guest_phone IS NULL
            AND cancel_token_hash IS NULL)
    );

COMMENT ON CONSTRAINT chk_appointment_guest_fields ON appointments IS
    'V206: V199''s rule byte-for-byte with cancel_token replaced by cancel_token_hash. Header and '
    'children must never diverge on this rule.';

-- ── lookup indexes (partial UNIQUE over non-NULL rows, as V90 / V124 were on the plaintext) ──────────
CREATE UNIQUE INDEX ux_bookings_cancel_token_hash ON bookings (cancel_token_hash)
    WHERE cancel_token_hash IS NOT NULL;
CREATE UNIQUE INDEX ux_appointments_cancel_token_hash ON appointments (cancel_token_hash)
    WHERE cancel_token_hash IS NOT NULL;

-- ── legacy-writer shim (removed with the plaintext column) ───────────────────────────────────────────
-- Fires on INSERT and on any UPDATE that names cancel_token. The current application never maps
-- cancel_token, so for it this is a no-op; it exists for writers that still do (rollback image,
-- raw-SQL fixtures, seed scripts).
CREATE OR REPLACE FUNCTION hash_legacy_cancel_token() RETURNS trigger AS $$
BEGIN
    IF NEW.cancel_token IS NOT NULL THEN
        NEW.cancel_token_hash := encode(sha256(convert_to(NEW.cancel_token::text, 'UTF8')), 'hex');
        NEW.cancel_token := NULL;
    ELSIF TG_OP = 'UPDATE' AND NEW.guest_pii_anonymised_at IS NOT NULL THEN
        -- A legacy anonymiser clearing the plaintext token: clear the hash with it (V199 anonymised arm).
        NEW.cancel_token_hash := NULL;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql
   -- Pin name resolution: only built-ins are referenced (encode/sha256/convert_to); pg_temp last so a
   -- temp-schema object can never shadow them.
   SET search_path = pg_catalog, pg_temp;

CREATE TRIGGER trg_bookings_hash_legacy_cancel_token
    BEFORE INSERT OR UPDATE OF cancel_token ON bookings
    FOR EACH ROW EXECUTE FUNCTION hash_legacy_cancel_token();

CREATE TRIGGER trg_appointments_hash_legacy_cancel_token
    BEFORE INSERT OR UPDATE OF cancel_token ON appointments
    FOR EACH ROW EXECUTE FUNCTION hash_legacy_cancel_token();
