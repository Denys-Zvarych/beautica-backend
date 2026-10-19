-- Phase 373 — Guest PII retention (website gap BG-2).
--
-- contract-of: V162
--
-- Expand/contract justification (phase 370 D6 gate — flagged for DROP + ADD CHECK of
-- chk_bookings_guest_fields / chk_appointment_guest_fields and the two new *_anonymised_scope CHECKs):
-- safe against the release running before this one (rollback = previous image on this schema).
--   * For every row with guest_pii_anonymised_at IS NULL, each new guest-fields CHECK is EXACTLY the
--     V162 CHECK (the live LINK / walk-in disjuncts are V162's clauses + "marker IS NULL"; the APP,
--     STAFF-registered and DETACHED arms are byte-for-byte). The previous app does not map
--     guest_pii_anonymised_at at all, so every row it INSERTs has the marker NULL (column default),
--     and its UPDATEs (Booking @DynamicUpdate; Appointment full-column but over mapped columns only)
--     never touch the marker. Its writes are therefore accepted iff V162 accepted them.
--   * Existing rows all have the marker NULL when this runs, so the CHECKs validate exactly as V162's.
--   * *_anonymised_scope (marker NULL OR client_id NULL) is vacuously true for marker-NULL rows.
--   * The only writes newly rejected are those putting PII back onto a row THIS release anonymised —
--     intended, and the previous app has no path that writes guest name/surname/phone or client notes
--     onto an existing guest row.
--
-- Guest (LINK) and walk-in (STAFF, client_id NULL) rows carry a non-user's name, surname, phone and
-- free-text notes. GuestPiiAnonymisationService overwrites them 12 months after bookings.ends_at
-- (GuestPiiRetentionJob) or on request (POST /api/v1/internal/guest-pii/erase):
--   guest_name -> 'Гість', guest_surname / guest_phone / client_comment / client_cancellation_note /
--   cancel_token -> NULL, guest_pii_anonymised_at -> now(). provider_comment is kept.
--
-- The live CHECKs (V162 arms, validated by V163) require guest_name AND guest_phone on a LINK row and
-- also a non-blank guest_surname on a STAFF walk-in row, so an anonymised row would satisfy no arm.
-- Each guest arm (LINK, STAFF walk-in) is split on guest_pii_anonymised_at:
--   * anonymised (marker set): sentinel name present; surname, phone, cancel_token, client_comment
--     and client_cancellation_note all NULL. Tight on purpose: a half-wiped row is unwritable, and a
--     later UPDATE can never put PII back onto a row marked anonymised (it would match neither
--     disjunct) — the marker is one-way at the DB level.
--   * live (marker NULL): the V162 clauses, byte-for-byte, plus "guest_pii_anonymised_at IS NULL".
-- The APP, STAFF-registered-client and DETACHED arms are V162 byte-for-byte.
--
-- Locking: Flyway runs this file in ONE transaction. The ADD COLUMN / DROP CONSTRAINT / ADD
-- CONSTRAINT statements take ACCESS EXCLUSIVE on bookings and appointments, and that lock is held
-- until COMMIT — through the CHECK validation scan and the (non-concurrent) CREATE INDEX builds
-- below. Readers and writers of both tables block for the whole migration. Acceptable because the
-- tables are small at this stage; lock_timeout bounds the wait to ACQUIRE the lock (the migration
-- fails fast instead of queueing behind a long transaction). The constraints are added validated
-- (no NOT VALID + VALIDATE split): inside one transaction that split buys no lock reduction.
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
                    AND cancel_token IS NULL
                    AND client_comment IS NULL
                    AND client_cancellation_note IS NULL)
             OR (guest_pii_anonymised_at IS NULL
                    AND guest_name IS NOT NULL
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
            AND cancel_token IS NULL)
    );

COMMENT ON CONSTRAINT chk_bookings_guest_fields ON bookings IS
    'Phase 373 splits the LINK arm and the STAFF walk-in branch of V162 on guest_pii_anonymised_at: '
    'ANONYMISED (marker set, sentinel name, surname/phone/token/client notes NULL) or LIVE (marker '
    'NULL + the V162 clauses). PII can never be written back onto an anonymised row. APP, '
    'STAFF-registered and DETACHED arms are V162 byte-for-byte.';

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
                    AND cancel_token IS NULL
                    AND client_comment IS NULL
                    AND client_cancellation_note IS NULL)
             OR (guest_pii_anonymised_at IS NULL
                    AND guest_name IS NOT NULL
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
            AND cancel_token IS NULL)
    );

COMMENT ON CONSTRAINT chk_appointment_guest_fields ON appointments IS
    'Phase 373 widens V162 with the same ANONYMISED disjunct as chk_bookings_guest_fields. Header '
    'and children must never diverge on this rule.';

ALTER TABLE appointments
    ADD CONSTRAINT chk_appointments_guest_pii_anonymised_scope CHECK (
        guest_pii_anonymised_at IS NULL OR client_id IS NULL
    );

-- ── indexes ─────────────────────────────────────────────────────────────────────────────────────
-- Every predicate mirrors the queries' fixed filter (client_id IS NULL AND guest_pii_anonymised_at
-- IS NULL AND client_detached_at IS NULL) so detached ex-client rows never sit in these indexes.

-- Retention job: due booking rows ordered by ends_at (mirrors the claim's WHERE + ORDER BY).
CREATE INDEX idx_bookings_guest_pii_due ON bookings (ends_at)
    WHERE client_id IS NULL AND guest_pii_anonymised_at IS NULL AND client_detached_at IS NULL;

-- Retention job leftover sweep for visit headers (child-less headers + headers whose last child was
-- anonymised by a concurrent instance): due by the header's own created_at. Headers of claimed
-- bookings are reached through bookings' PK + idx_bookings_appointment instead.
CREATE INDEX idx_appointments_guest_pii_due ON appointments (created_at)
    WHERE client_id IS NULL AND guest_pii_anonymised_at IS NULL AND client_detached_at IS NULL;

-- Retention job ended-visit sweep: headers reached from recently anonymised visit items (a header
-- whose children were claimed by two racing instances). Predicate matches that subselect.
CREATE INDEX idx_bookings_guest_pii_anonymised_visit ON bookings (guest_pii_anonymised_at)
    WHERE appointment_id IS NOT NULL AND guest_pii_anonymised_at IS NOT NULL;

-- Manual erase by phone. V138's idx_bookings_staff_walkin_phone covers STAFF only; LINK had none.
CREATE INDEX idx_bookings_guest_phone_open ON bookings (guest_phone)
    WHERE client_id IS NULL AND guest_pii_anonymised_at IS NULL AND client_detached_at IS NULL;

CREATE INDEX idx_appointments_guest_phone_open ON appointments (guest_phone)
    WHERE client_id IS NULL AND guest_pii_anonymised_at IS NULL AND client_detached_at IS NULL;
