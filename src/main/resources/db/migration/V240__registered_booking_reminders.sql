-- non-destructive
-- Reason: two new partial indexes (no existing index touched) and chk_outbox_event re-added as a strict superset
-- (every prior value kept, one new value).
--
-- V240 — Phase 494 (NTF-47): 24 h + 2 h reminder e-mails for registered (APP) clients.
--
-- WHY
-- BookingReminderJob's sweeps gain a registered leg beside the guest leg. Its two finders
-- (BookingRepository.findRegisteredBookingsForReminder / findRegisteredBookingsForTwoHourReminder) filter
-- booking_source = 'APP' AND status = 'CONFIRMED' AND <flag> = FALSE over a starts_at window. The guest partial
-- indexes from V226 are LINK | STAFF only, so they cannot serve an APP predicate; D3 adds separate APP indexes
-- instead of widening the guest ones, so the guest query plans do not change.
-- The claimed booking is handed to the outbox as BOOKING_REMINDER (aggregate_id = booking id, payload ids only:
-- {"bookingId","kind","claimedAt"}); V32's chk_outbox_booking_aggregate (aggregate_id NOT NULL for non-INVITE) holds.
--
-- SAFETY
-- Expand-only. CREATE INDEX (not CONCURRENTLY: Flyway runs each migration in a transaction) takes a SHARE lock on
-- bookings for the build; both indexes are partial over un-reminded CONFIRMED APP rows only, so the build is small.
-- lock_timeout bounds the wait for the lock; a timeout fails the deploy cleanly, nothing half-applied.
-- History of chk_outbox_event: V32 created it; V94 / V109 / V131 / V156 / V161 / V184 / V223 / V238 widened it to
-- thirteen values. Every prior value retained byte-for-byte; DROP ... IF EXISTS keeps a clean-DB replay deterministic.
-- The phase doc first named V239; V239 is phase 493's, so this is the next free version.
-- Rollback image: the previous app never writes BOOKING_REMINDER and never plans on the new indexes.
SET LOCAL lock_timeout = '5s';

CREATE INDEX IF NOT EXISTS idx_bookings_reminder_app ON bookings (starts_at)
    WHERE booking_source = 'APP' AND status = 'CONFIRMED' AND reminder_sent = FALSE;

CREATE INDEX IF NOT EXISTS idx_bookings_reminder_2h_app ON bookings (starts_at)
    WHERE booking_source = 'APP' AND status = 'CONFIRMED' AND reminder_2h_sent = FALSE;

ALTER TABLE notification_outbox DROP CONSTRAINT IF EXISTS chk_outbox_event;

ALTER TABLE notification_outbox
    ADD CONSTRAINT chk_outbox_event CHECK (
        event_type IN ('NEW_BOOKING','STATUS_CHANGED','CLIENT_CANCELLED','INVITE',
                       'BOOKING_RESCHEDULED','REVIEW_REQUESTED','CLOSURE_REMINDER','SALON_CLOSED',
                       'MASTER_REMOVED','INAPP_PUSH',
                       'MASTER_BOOKING_RESCHEDULED','MASTER_BOOKING_DECLINED',
                       'REQUEST_DECIDED','BOOKING_REMINDER'));
