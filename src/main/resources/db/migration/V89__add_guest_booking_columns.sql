-- Phase 13.3 — Guest Booking Creation & Auto-Confirm.
--
-- Adds the columns that distinguish a guest (LINK) booking — created by a
-- phone-verified, account-less client via beautica.app/book/{slug} — from a
-- regular APP booking placed by a registered CLIENT.
--
-- booking_source defaults to 'APP' so every pre-existing booking row keeps its
-- meaning untouched (Anti-Bug §O-1: no NOT NULL backfill failure on existing rows).
ALTER TABLE bookings
    ADD COLUMN booking_source VARCHAR(10) NOT NULL DEFAULT 'APP'
        CONSTRAINT chk_bookings_source CHECK (booking_source IN ('APP', 'LINK')),
    ADD COLUMN guest_name     VARCHAR(100),
    ADD COLUMN guest_surname  VARCHAR(100),
    ADD COLUMN guest_phone    VARCHAR(20),
    ADD COLUMN cancel_token   UUID UNIQUE,
    ADD COLUMN reminder_sent  BOOLEAN NOT NULL DEFAULT FALSE;

-- A guest (LINK) booking has no registered account, so client_id is NULL for it.
-- Relax the V18 NOT NULL; the chk_bookings_guest_fields CHECK below re-tightens it
-- per source (APP ⇒ client_id NOT NULL, LINK ⇒ client_id NULL) so an APP booking
-- can still never be account-less.
ALTER TABLE bookings ALTER COLUMN client_id DROP NOT NULL;

-- Polymorphic CHECK (Anti-Bug §O-3 / §O-5): a LINK booking MUST carry the guest
-- identity + cancel token; an APP booking MUST NOT. This enforces at the DB layer
-- the same invariant GuestBookingService asserts in the application layer, so a
-- controller/service bug cannot persist a half-populated guest row.
ALTER TABLE bookings
    ADD CONSTRAINT chk_bookings_guest_fields CHECK (
        (booking_source = 'LINK'
            AND client_id IS NULL
            AND guest_name IS NOT NULL
            AND guest_phone IS NOT NULL
            AND cancel_token IS NOT NULL)
        OR
        (booking_source = 'APP'
            AND client_id IS NOT NULL
            AND guest_name IS NULL
            AND guest_surname IS NULL
            AND guest_phone IS NULL
            AND cancel_token IS NULL)
    );

-- Phone format guard (Anti-Bug §O-2): guest_phone, when present, is the E.164
-- sub-claim copied from the guest JWT. Reject anything that is not +<digits>.
ALTER TABLE bookings
    ADD CONSTRAINT chk_bookings_guest_phone_format CHECK (
        guest_phone IS NULL OR guest_phone ~ '^\+[0-9]{6,18}$'
    );

-- Cancel-token lookup for the public cancellation page (Phase 13.4). Partial:
-- only LINK rows ever carry a token (Anti-Bug §O-6).
CREATE INDEX idx_bookings_cancel_token ON bookings (cancel_token)
    WHERE cancel_token IS NOT NULL;

-- Reminder-sweep predicate mirror (Anti-Bug §O-6): the BookingReminderJob query
-- filters LINK + reminder_sent = FALSE + starts_at BETWEEN, so the partial index
-- matches exactly the rows the hourly sweep scans.
CREATE INDEX idx_bookings_reminder ON bookings (starts_at)
    WHERE booking_source = 'LINK' AND reminder_sent = FALSE;
