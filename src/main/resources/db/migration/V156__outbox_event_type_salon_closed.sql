-- Phase 293 (delta on 269) — widen the notification_outbox event_type CHECK to admit SALON_CLOSED.
--
-- History of chk_outbox_event:
--   V32  created it as CHECK (event_type IN ('NEW_BOOKING','STATUS_CHANGED','CLIENT_CANCELLED','INVITE'))
--   V94  widened it to add 'BOOKING_RESCHEDULED' (five values).
--   V109 widened it to add 'REVIEW_REQUESTED' (six values).
--   V131 widened it to add 'CLOSURE_REMINDER' (seven values).
--
-- Phase 269/293 ("salon deletion: cancel future bookings and notify") auto-declines every future
-- CONFIRMED booking at a deleted salon and enqueues one SALON_CLOSED outbox event per affected
-- VISIT (D12 — deduplicated on coalesce(appointment_id, id), never one per declined booking).
-- Without this widening, Hibernate would persist the new enum value and the INSERT would fail the
-- CHECK at runtime, not at startup.
--
-- V32's two companion CHECKs already admit the new value unchanged: SALON_CLOSED always carries a
-- non-null aggregate_id (the representative booking of the visit) and no payload beyond what the
-- drain worker hydrates from the booking graph, so chk_outbox_invite_payload /
-- chk_outbox_booking_aggregate need no change.
--
-- The shipped V32/V94/V109/V131 constraints are NEVER edited (immutable migrations); this
-- fix-forward migration drops and re-adds the constraint with the extra value. Idempotent:
-- DROP ... IF EXISTS makes a clean-DB replay deterministic. All prior values are retained
-- byte-for-byte.
ALTER TABLE notification_outbox DROP CONSTRAINT IF EXISTS chk_outbox_event;

ALTER TABLE notification_outbox
    ADD CONSTRAINT chk_outbox_event CHECK (
        event_type IN ('NEW_BOOKING','STATUS_CHANGED','CLIENT_CANCELLED','INVITE',
                       'BOOKING_RESCHEDULED','REVIEW_REQUESTED','CLOSURE_REMINDER','SALON_CLOSED'));
