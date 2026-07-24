-- Phase 19.2 — widen the notification_outbox event_type CHECK to admit BOOKING_RESCHEDULED.
--
-- V32 created chk_outbox_event as:
--     CHECK (event_type IN ('NEW_BOOKING','STATUS_CHANGED','CLIENT_CANCELLED','INVITE'))
--
-- The client booking-reschedule endpoint (PATCH /bookings/{id}/reschedule) enqueues a new
-- BOOKING_RESCHEDULED outbox event so the drain worker can notify the provider (master /
-- salon-admin) to re-approve the moved booking. Without this widening Hibernate would persist
-- the new enum value and the INSERT would fail the CHECK.
--
-- The shipped V32 constraint is NEVER edited (immutable migration); this fix-forward migration
-- drops and re-adds the constraint with the extra value. Idempotent: DROP ... IF EXISTS makes a
-- clean-DB replay deterministic. All prior values are retained byte-for-byte.
ALTER TABLE notification_outbox DROP CONSTRAINT IF EXISTS chk_outbox_event;

ALTER TABLE notification_outbox
    ADD CONSTRAINT chk_outbox_event CHECK (
        event_type IN ('NEW_BOOKING','STATUS_CHANGED','CLIENT_CANCELLED','INVITE','BOOKING_RESCHEDULED'));
