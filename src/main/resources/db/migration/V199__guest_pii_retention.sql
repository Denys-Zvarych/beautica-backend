-- Phase 373 — Guest PII retention (website gap BG-2).
--
-- Guest (LINK) and walk-in (STAFF, client_id NULL) rows carry a non-user's name, surname, phone and
-- free-text notes. GuestPiiAnonymisationService overwrites them 12 months after bookings.ends_at
-- (GuestPiiRetentionJob) or on request (POST /api/v1/internal/guest-pii/erase):
--   guest_name -> 'Гість', guest_surname / guest_phone / client_comment / client_cancellation_note /
--   cancel_token -> NULL, guest_pii_anonymised_at -> now(). provider_comment is kept.
--
-- The live CHECKs (V162 arms, validated by V163) require guest_name AND guest_phone on a LINK row and
-- also a non-blank guest_surname on a STAFF walk-in row, so an anonymised row would satisfy no arm.
-- Each guest arm gains ONE disjunct for the anonymised shape; every other arm and clause is copied
-- byte-for-byte from V162. The anonymised disjunct is deliberately tight (name present, everything
-- else NULL) so a half-wiped row stays unwritable — the same "one UPDATE" discipline as V162's
-- DETACHED arm.
--
-- Precedent for NOT VALID + VALIDATE: V139 -> V140, V162 -> V163. Both run here (in one file) because
-- the tables are small pre-launch; VALIDATE takes only SHARE UPDATE EXCLUSIVE.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE bookings     ADD COLUMN guest_pii_anonymised_at TIMESTAMPTZ NULL;
ALTER TABLE appointments ADD COLUMN guest_pii_anonymised_at TIMESTAMPTZ NULL;

COMMENT ON COLUMN bookings.guest_pii_anonymised_at IS
    'Phase 373. When the guest/walk-in PII on this row was irreversibly anonymised (retention job or '
    'internal erase endpoint). NULL <=> not anonymised. Idempotency marker for the job and the '
    'discriminator for the anonymised disjunct of chk_bookings_guest_fields.';
COMMENT ON COLUMN appointments.guest_pii_anonymised_at IS
    'Phase 373. Mirror of bookings.guest_pii_anonymised_at for the visit header.';

-- ── bookings ────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE bookings DROP CONSTRAINT chk_bookings_guest_fields;
ALTER TABLE bookings
    ADD CONSTRAINT chk_bookings_guest_fields CHECK (
        (booking_source = 'LINK'
            AND client_detached_at IS NULL
            AND client_id IS NULL
            AND (
                (guest_pii_anonymised_at IS NOT NULL
                    AND guest_name IS NOT NULL AND btrim(guest_name) <> ''
                    AND guest_surname IS NULL
                    AND guest_phone IS NULL
                    AND cancel_token IS NULL)
             OR (guest_name IS NOT NULL
                    AND guest_phone IS NOT NULL
                    AND (cancel_token IS NOT NULL
                         OR status IN ('CANCELLED', 'COMPLETED', 'NOT_COMPLETED', 'DECLINED')))
            ))
     OR (booking_source = 'APP'
            AND client_detached_at IS NULL
            AND client_id IS NOT NULL
            AND guest_name IS NULL
            AND guest_surname IS NULL
            AND guest_phone IS NULL
            AND cancel_token IS NULL)
     OR (booking_source = 'STAFF'
            AND client_detached_at IS NULL
            AND cancel_token IS NULL
            AND (
                (client_id IS NOT NULL
                    AND guest_name IS NULL
                    AND guest_surname IS NULL
                    AND guest_phone IS NULL)
             OR (client_id IS NULL
                    AND guest_pii_anonymised_at IS NOT NULL
                    AND guest_name IS NOT NULL AND btrim(guest_name) <> ''
                    AND guest_surname IS NULL
                    AND guest_phone IS NULL)
             OR (client_id IS NULL
                    AND guest_name    IS NOT NULL AND btrim(guest_name)    <> ''
                    AND guest_surname IS NOT NULL AND btrim(guest_surname) <> ''
                    AND guest_phone IS NOT NULL)
            ))
     OR (client_detached_at IS NOT NULL
            AND client_id IS NULL
            AND guest_name IS NOT NULL AND btrim(guest_name) <> ''
            AND guest_surname IS NULL
            AND guest_phone IS NULL
            AND cancel_token IS NULL)
    ) NOT VALID;
ALTER TABLE bookings VALIDATE CONSTRAINT chk_bookings_guest_fields;

COMMENT ON CONSTRAINT chk_bookings_guest_fields ON bookings IS
    'Phase 373 widens V162 with an ANONYMISED disjunct inside the LINK arm and the STAFF walk-in '
    'branch (guest_pii_anonymised_at set, sentinel name, surname/phone/token NULL). All other '
    'clauses are V162 byte-for-byte; see V162 for the DETACHED arm rationale.';

-- A registered client's row is never anonymised (only client_id NULL rows are in scope).
ALTER TABLE bookings
    ADD CONSTRAINT chk_bookings_guest_pii_anonymised_scope CHECK (
        guest_pii_anonymised_at IS NULL OR client_id IS NULL
    );

-- ── appointments ────────────────────────────────────────────────────────────────────────────────
ALTER TABLE appointments DROP CONSTRAINT chk_appointment_guest_fields;
ALTER TABLE appointments
    ADD CONSTRAINT chk_appointment_guest_fields CHECK (
        (booking_source = 'LINK'
            AND client_detached_at IS NULL
            AND client_id IS NULL
            AND (
                (guest_pii_anonymised_at IS NOT NULL
                    AND guest_name IS NOT NULL AND btrim(guest_name) <> ''
                    AND guest_surname IS NULL
                    AND guest_phone IS NULL
                    AND cancel_token IS NULL)
             OR (guest_name IS NOT NULL
                    AND guest_phone IS NOT NULL
                    AND (cancel_token IS NOT NULL
                         OR status IN ('CANCELLED', 'COMPLETED', 'NOT_COMPLETED', 'DECLINED')))
            ))
     OR (booking_source = 'APP'
            AND client_detached_at IS NULL
            AND client_id IS NOT NULL
            AND guest_name IS NULL
            AND guest_surname IS NULL
            AND guest_phone IS NULL
            AND cancel_token IS NULL)
     OR (booking_source = 'STAFF'
            AND client_detached_at IS NULL
            AND cancel_token IS NULL
            AND (
                (client_id IS NOT NULL
                    AND guest_name IS NULL
                    AND guest_surname IS NULL
                    AND guest_phone IS NULL)
             OR (client_id IS NULL
                    AND guest_pii_anonymised_at IS NOT NULL
                    AND guest_name IS NOT NULL AND btrim(guest_name) <> ''
                    AND guest_surname IS NULL
                    AND guest_phone IS NULL)
             OR (client_id IS NULL
                    AND guest_name    IS NOT NULL AND btrim(guest_name)    <> ''
                    AND guest_surname IS NOT NULL AND btrim(guest_surname) <> ''
                    AND guest_phone IS NOT NULL)
            ))
     OR (client_detached_at IS NOT NULL
            AND client_id IS NULL
            AND guest_name IS NOT NULL AND btrim(guest_name) <> ''
            AND guest_surname IS NULL
            AND guest_phone IS NULL
            AND cancel_token IS NULL)
    ) NOT VALID;
ALTER TABLE appointments VALIDATE CONSTRAINT chk_appointment_guest_fields;

COMMENT ON CONSTRAINT chk_appointment_guest_fields ON appointments IS
    'Phase 373 widens V162 with the same ANONYMISED disjunct as chk_bookings_guest_fields. Header '
    'and children must never diverge on this rule.';

ALTER TABLE appointments
    ADD CONSTRAINT chk_appointments_guest_pii_anonymised_scope CHECK (
        guest_pii_anonymised_at IS NULL OR client_id IS NULL
    );

-- ── indexes ─────────────────────────────────────────────────────────────────────────────────────
-- Retention job: due rows ordered by ends_at (mirrors the job's WHERE + ORDER BY).
CREATE INDEX idx_bookings_guest_pii_due ON bookings (ends_at)
    WHERE client_id IS NULL AND guest_pii_anonymised_at IS NULL;

-- Manual erase by phone. V138's idx_bookings_staff_walkin_phone covers STAFF only; LINK had none.
CREATE INDEX idx_bookings_guest_phone_open ON bookings (guest_phone)
    WHERE client_id IS NULL AND guest_pii_anonymised_at IS NULL;

CREATE INDEX idx_appointments_guest_phone_open ON appointments (guest_phone)
    WHERE client_id IS NULL AND guest_pii_anonymised_at IS NULL;
