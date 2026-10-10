-- Phase 339 — widen the notification_outbox event_type CHECK to admit INAPP_PUSH.
--
-- History of chk_outbox_event:
--   V32  created it (four values); V94 / V109 / V131 / V156 / V161 widened it to nine values.
--
-- INAPP_PUSH carries aggregate_id = in_app_notification.id and NO payload (ids only — the push
-- title/body are rendered at send time, never stored). V32's companion CHECKs already admit it:
-- aggregate_id is non-null (chk_outbox_booking_aggregate) and payload is nullable for every
-- non-INVITE event (chk_outbox_invite_payload).
--
-- Applied migrations are immutable: fix-forward, drop-and-re-add with every prior value retained
-- byte-for-byte. DROP ... IF EXISTS keeps a clean-DB replay deterministic.
ALTER TABLE notification_outbox DROP CONSTRAINT IF EXISTS chk_outbox_event;

ALTER TABLE notification_outbox
    ADD CONSTRAINT chk_outbox_event CHECK (
        event_type IN ('NEW_BOOKING','STATUS_CHANGED','CLIENT_CANCELLED','INVITE',
                       'BOOKING_RESCHEDULED','REVIEW_REQUESTED','CLOSURE_REMINDER','SALON_CLOSED',
                       'MASTER_REMOVED','INAPP_PUSH'));
