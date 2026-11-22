-- non-destructive
-- Reason: adds two nullable columns and two new partial indexes; the two re-added guest-field CHECKs are V226's text plus ONE extra OR-arm, so they are strictly looser than the ones they replace and no existing row or previous-app write can violate them.
-- Phase 527 — one client identity by phone. A LINK or STAFF booking whose E.164 phone equals the verified phone of an
-- active CLIENT account is ATTACHED to that account: client_id is set, guest_phone is cleared, and the new columns record
-- when and why (B527 D1/D3).
--
-- WHAT
--   1. bookings / appointments: client_linked_at timestamptz + client_link_reason varchar(16), NULL for every row that
--      was never attached. Both-or-neither CHECK; reason in ('BOOKING','PHONE_VERIFIED','BACKFILL').
--   2. chk_bookings_guest_fields / chk_appointment_guest_fields: V226's text byte-for-byte plus ONE new arm — the
--      ATTACHED guest row (source LINK|STAFF, client_id set, client_link_reason set, not detached, not anonymised,
--      guest_name kept, guest_phone NULL). The cancel token (hash + sealed) is not constrained on that arm: the link
--      the guest already holds keeps working ONLY for a create-time attach (BOOKING); the retroactive attach
--      (PHONE_VERIFIED / BACKFILL) NULLs the token, which this arm also admits (D3). The DETACHED arm (client_detached_at) is unchanged: self-delete
--      (CLI-24) nulls the token on an attached row, so the existing arm admits it (CLI-66).
--   3. Two partial indexes for the registered reminder sweeps. V240's idx_bookings_reminder_app / _2h_app are
--      `booking_source = 'APP'`; the sweeps now also read attached rows (`client_link_reason IS NOT NULL`), so a
--      BitmapOr of the two serves the OR predicate. The V240 indexes stay.
--
-- No index for the attach lookup: it uses idx_bookings_guest_phone_starts_at (V259, partial on client_id IS NULL) and
-- the verified-phone unique index ux_users_verified_phone (V244).
--
-- All four CHECKs are added NOT VALID (enforced for every new write at once, no row scan here) and VALIDATEd by V261
-- (a separate migration, hence a separate Flyway transaction from this one), BEFORE the backfill — the V114/V115 / V230+V231 shape, so the ACCESS EXCLUSIVE window of this
-- migration is metadata-only. Every existing row already satisfies them (the link columns are NULL; the guest-fields
-- text is V226's plus one OR-arm).
--
-- LOCKING — one Flyway transaction; ACCESS EXCLUSIVE on bookings and appointments through the column adds, the
-- NOT VALID CHECK swaps and the index builds. lock_timeout bounds the wait to acquire the lock, statement_timeout the
-- index builds (same as V226). Both tables are small relative to the budget: the scans read no heap beyond the one pass.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

-- ── 1. the link columns ──────────────────────────────────────────────────────────────────────────────
ALTER TABLE bookings
    ADD COLUMN client_linked_at   TIMESTAMPTZ,
    ADD COLUMN client_link_reason VARCHAR(16);
ALTER TABLE appointments
    ADD COLUMN client_linked_at   TIMESTAMPTZ,
    ADD COLUMN client_link_reason VARCHAR(16);

ALTER TABLE bookings
    ADD CONSTRAINT chk_bookings_client_link CHECK (
        (client_linked_at IS NULL) = (client_link_reason IS NULL)
        AND (client_link_reason IS NULL OR client_link_reason IN ('BOOKING', 'PHONE_VERIFIED', 'BACKFILL')))
    NOT VALID;
ALTER TABLE appointments
    ADD CONSTRAINT chk_appointments_client_link CHECK (
        (client_linked_at IS NULL) = (client_link_reason IS NULL)
        AND (client_link_reason IS NULL OR client_link_reason IN ('BOOKING', 'PHONE_VERIFIED', 'BACKFILL')))
    NOT VALID;

COMMENT ON COLUMN bookings.client_linked_at IS
    'V260 (phase 527). When a guest (LINK) / walk-in (STAFF) booking was attached to a CLIENT account by its verified phone. NULL = never attached.';
COMMENT ON COLUMN bookings.client_link_reason IS
    'V260 (phase 527). BOOKING = attached at create, PHONE_VERIFIED = attached when the account verified the phone, BACKFILL = V261. NULL <=> client_linked_at NULL.';
COMMENT ON COLUMN appointments.client_linked_at IS
    'V260 (phase 527). Mirror of bookings.client_linked_at for the visit header; header and children never diverge.';
COMMENT ON COLUMN appointments.client_link_reason IS
    'V260 (phase 527). Mirror of bookings.client_link_reason for the visit header.';

-- ── 2. guest-field CHECKs: V226 + the attached arm ──────────────────────────────────────────────────
ALTER TABLE bookings     DROP CONSTRAINT chk_bookings_guest_fields;
ALTER TABLE appointments DROP CONSTRAINT chk_appointment_guest_fields;

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
            AND (
                (client_id IS NOT NULL
                    AND guest_name IS NULL
                    AND guest_surname IS NULL
                    AND guest_phone IS NULL
                    AND cancel_token_hash IS NULL)
             OR (client_id IS NULL
                    AND guest_pii_anonymised_at IS NOT NULL
                    AND guest_name IS NOT NULL AND btrim(guest_name) <> ''
                    AND guest_surname IS NULL
                    AND guest_phone IS NULL
                    AND cancel_token_hash IS NULL
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
     -- V260 (phase 527): the ATTACHED guest row.
     OR (booking_source IN ('LINK', 'STAFF')
            AND client_link_reason IS NOT NULL
            AND client_detached_at IS NULL
            AND client_id IS NOT NULL
            AND guest_pii_anonymised_at IS NULL
            AND guest_name IS NOT NULL AND btrim(guest_name) <> ''
            AND guest_phone IS NULL)
    ) NOT VALID;

COMMENT ON CONSTRAINT chk_bookings_guest_fields ON bookings IS
    'V260 (phase 527): V226''s rule plus the ATTACHED arm (LINK|STAFF row attached to a client account by its verified phone: client_id + client_link_reason set, guest_phone NULL, guest name kept, cancel token free). Every other arm is V226 byte-for-byte.';

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
            AND (
                (client_id IS NOT NULL
                    AND guest_name IS NULL
                    AND guest_surname IS NULL
                    AND guest_phone IS NULL
                    AND cancel_token_hash IS NULL)
             OR (client_id IS NULL
                    AND guest_pii_anonymised_at IS NOT NULL
                    AND guest_name IS NOT NULL AND btrim(guest_name) <> ''
                    AND guest_surname IS NULL
                    AND guest_phone IS NULL
                    AND cancel_token_hash IS NULL
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
     -- V260 (phase 527): the ATTACHED visit header.
     OR (booking_source IN ('LINK', 'STAFF')
            AND client_link_reason IS NOT NULL
            AND client_detached_at IS NULL
            AND client_id IS NOT NULL
            AND guest_pii_anonymised_at IS NULL
            AND guest_name IS NOT NULL AND btrim(guest_name) <> ''
            AND guest_phone IS NULL)
    ) NOT VALID;

COMMENT ON CONSTRAINT chk_appointment_guest_fields ON appointments IS
    'V260 (phase 527): mirror of chk_bookings_guest_fields — header and children must never diverge on this rule.';

-- ── 3. registered reminder sweeps also read attached rows ────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_bookings_reminder_attached ON bookings (starts_at)
    WHERE client_link_reason IS NOT NULL AND status = 'CONFIRMED' AND reminder_sent = FALSE;

CREATE INDEX IF NOT EXISTS idx_bookings_reminder_2h_attached ON bookings (starts_at)
    WHERE client_link_reason IS NOT NULL AND status = 'CONFIRMED' AND reminder_2h_sent = FALSE;
