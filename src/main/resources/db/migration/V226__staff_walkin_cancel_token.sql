-- non-destructive
-- Reason: both re-added CHECKs are strictly looser than the V206 ones they replace (only the STAFF walk-in arm may now carry a token), so no existing row or previous-app write can violate them.
-- Phase 412 — walk-in cancel link. Reverses phases 168 / 171 / 254 D (STAFF => cancel_token NULL).
--
-- A STAFF walk-in visit (client_id NULL, guest phone typed in by the provider) now carries a one-time
-- guest cancel token on its appointments HEADER, exactly like a LINK visit (StaffBookingService, same
-- CancelTokenHasher hash column). The child bookings rows of a STAFF visit stay tokenless: the per-item
-- token a LINK child carries is never accepted by any lookup (findByCancelTokenHashWithGraph requires
-- appointment IS NULL), so issuing one here would only store a dead capability.
--
-- D2 — "no token without a phone": cancel_token_hash moves from the STAFF arm's top level into its three
-- sub-arms. The registered-client and anonymised sub-arms have guest_phone NULL and keep
-- cancel_token_hash IS NULL; the LIVE walk-in sub-arm (guest_phone NOT NULL) may carry one. Equivalent to
-- AND-ing  booking_source <> 'STAFF' OR guest_phone IS NOT NULL OR cancel_token_hash IS NULL  onto V206's
-- rule with the STAFF top-level clause removed. Every other arm is V206 byte-for-byte.
--
-- Phase 413 D4 (walk-ins in both reminder sweeps): the two reminder partial indexes widen from LINK to
-- LINK | STAFF so the sweep predicate (LINK, or STAFF with a phone) stays index-served.
--
-- Locking as V206: one Flyway transaction, ACCESS EXCLUSIVE on bookings and appointments through the
-- CHECK scans and index builds; lock_timeout bounds the wait to acquire, statement_timeout the scans.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE bookings     DROP CONSTRAINT chk_bookings_guest_fields;
ALTER TABLE appointments DROP CONSTRAINT chk_appointment_guest_fields;

-- ── bookings ─────────────────────────────────────────────────────────────────────────────────────────
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
    );

COMMENT ON CONSTRAINT chk_bookings_guest_fields ON bookings IS
    'V226 (phase 412): V206''s rule with the STAFF arm''s cancel_token_hash IS NULL moved into its '
    'registered-client and anonymised sub-arms, so a LIVE walk-in (guest phone present) may carry a '
    'cancel token and no STAFF row carries one without a phone. Every other arm is V206 byte-for-byte.';

-- ── appointments ─────────────────────────────────────────────────────────────────────────────────────
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
    );

COMMENT ON CONSTRAINT chk_appointment_guest_fields ON appointments IS
    'V226 (phase 412): mirror of chk_bookings_guest_fields — a LIVE STAFF walk-in header may carry the '
    'visit cancel token; header and children must never diverge on this rule.';

COMMENT ON COLUMN bookings.cancel_token_hash IS
    'V206. Lower-hex SHA-256 of the guest one-time cancel token (CancelTokenHasher). The raw token is '
    'never stored. NULL once consumed / terminal-by-provider / anonymised, on APP rows, and on STAFF '
    'rows (V226: a STAFF walk-in visit carries its token on the appointments header only).';
COMMENT ON COLUMN appointments.cancel_token_hash IS
    'V206. Mirror of bookings.cancel_token_hash for the guest visit header; since V226 also set on a '
    'STAFF walk-in header (phase 412).';

-- ── reminder sweeps (phase 413 D4): LINK | STAFF partial indexes ─────────────────────────────────────
DROP INDEX idx_bookings_reminder;
CREATE INDEX idx_bookings_reminder ON bookings (starts_at)
    WHERE booking_source IN ('LINK', 'STAFF') AND reminder_sent = FALSE;

DROP INDEX idx_bookings_reminder_2h;
CREATE INDEX idx_bookings_reminder_2h ON bookings (starts_at)
    WHERE booking_source IN ('LINK', 'STAFF') AND status = 'CONFIRMED' AND reminder_2h_sent = FALSE;
