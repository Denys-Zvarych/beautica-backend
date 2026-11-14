-- non-destructive
-- Reason: chk_outbox_event is re-added as a strict superset (every prior value kept, two new values).
--
-- V243 — Phase 496 audit-fix cycle 1 (NTF-49): the registered client's cancel / reschedule receipts get their OWN
-- outbox rows, CLIENT_CANCEL_RECEIPT and CLIENT_RESCHEDULE_RECEIPT, beside the master's CLIENT_CANCELLED /
-- BOOKING_RESCHEDULED rows. One row per recipient = one retry state per recipient: a failed receipt send no longer
-- re-sends the master's e-mail on every retry (and vice versa).
-- aggregate_id = the booking id, payload NULL (ids only, no PII); V32's chk_outbox_booking_aggregate
-- (aggregate_id NOT NULL for non-INVITE) holds.
--
-- SAFETY
-- Expand-only. DROP + ADD CONSTRAINT re-validates the existing rows under a short ACCESS EXCLUSIVE lock on
-- notification_outbox (a small, swept table) — the same shape as V238 / V240. lock_timeout bounds the wait; a timeout
-- fails the deploy cleanly, nothing half-applied.
-- History of chk_outbox_event: V32 created it; V94 / V109 / V131 / V156 / V161 / V184 / V223 / V238 / V240 widened it
-- to fourteen values. Every prior value retained byte-for-byte; DROP ... IF EXISTS keeps a clean-DB replay
-- deterministic.
-- Rollback image: the previous app never writes either new value.
SET LOCAL lock_timeout = '5s';

ALTER TABLE notification_outbox DROP CONSTRAINT IF EXISTS chk_outbox_event;

ALTER TABLE notification_outbox
    ADD CONSTRAINT chk_outbox_event CHECK (
        event_type IN ('NEW_BOOKING','STATUS_CHANGED','CLIENT_CANCELLED','INVITE',
                       'BOOKING_RESCHEDULED','REVIEW_REQUESTED','CLOSURE_REMINDER','SALON_CLOSED',
                       'MASTER_REMOVED','INAPP_PUSH',
                       'MASTER_BOOKING_RESCHEDULED','MASTER_BOOKING_DECLINED',
                       'REQUEST_DECIDED','BOOKING_REMINDER',
                       'CLIENT_CANCEL_RECEIPT','CLIENT_RESCHEDULE_RECEIPT'));
