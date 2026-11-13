-- non-destructive
-- Reason: chk_outbox_event is re-added as a strict superset (every prior value kept, one new value).
-- Phase 491 — widen chk_outbox_event to admit REQUEST_DECIDED: the requester's e-mail when an admin
-- approves or rejects a category request or a service-type suggestion (NTF-35). One row per decision,
-- enqueued in the decision transaction. aggregate_id = the requester's user id (platform_categories.id
-- is BIGINT, so it cannot be the UUID aggregate); payload = ids + the approve flag only
-- {"kind","requestId","approved","recipientUserId"} — no name, reason or e-mail (read at drain time).
-- V32's chk_outbox_booking_aggregate (aggregate_id NOT NULL for non-INVITE) holds: the recipient is non-null.
-- History: V32 created it; V94 / V109 / V131 / V156 / V161 / V184 / V223 widened it to twelve values.
-- Every prior value retained byte-for-byte; DROP ... IF EXISTS keeps a clean-DB replay deterministic.
-- The phase doc named V235; V235-V237 were already taken, so this is the next free version.
SET LOCAL lock_timeout = '5s';

ALTER TABLE notification_outbox DROP CONSTRAINT IF EXISTS chk_outbox_event;

ALTER TABLE notification_outbox
    ADD CONSTRAINT chk_outbox_event CHECK (
        event_type IN ('NEW_BOOKING','STATUS_CHANGED','CLIENT_CANCELLED','INVITE',
                       'BOOKING_RESCHEDULED','REVIEW_REQUESTED','CLOSURE_REMINDER','SALON_CLOSED',
                       'MASTER_REMOVED','INAPP_PUSH',
                       'MASTER_BOOKING_RESCHEDULED','MASTER_BOOKING_DECLINED',
                       'REQUEST_DECIDED'));
