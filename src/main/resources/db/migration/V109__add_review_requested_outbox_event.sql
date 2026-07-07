-- Phase 18.1 — widen the notification_outbox event_type CHECK to admit REVIEW_REQUESTED.
--
-- History of chk_outbox_event:
--   V32  created it as CHECK (event_type IN ('NEW_BOOKING','STATUS_CHANGED','CLIENT_CANCELLED','INVITE'))
--   V94  widened it to add 'BOOKING_RESCHEDULED' (five values).
--
-- The CONFIRMED → COMPLETED booking transition now enqueues a dedicated REVIEW_REQUESTED
-- outbox event (in addition to the existing STATUS_CHANGED audit row) so the drain worker can
-- send the client a "please leave a review" email + push. Without this widening Hibernate would
-- persist the new enum value and the INSERT would fail the CHECK.
--
-- The shipped V32/V94 constraints are NEVER edited (immutable migrations); this fix-forward
-- migration drops and re-adds the constraint with the extra value. Idempotent: DROP ... IF EXISTS
-- makes a clean-DB replay deterministic. All prior values are retained byte-for-byte.
ALTER TABLE notification_outbox DROP CONSTRAINT IF EXISTS chk_outbox_event;

ALTER TABLE notification_outbox
    ADD CONSTRAINT chk_outbox_event CHECK (
        event_type IN ('NEW_BOOKING','STATUS_CHANGED','CLIENT_CANCELLED','INVITE','BOOKING_RESCHEDULED','REVIEW_REQUESTED'));
