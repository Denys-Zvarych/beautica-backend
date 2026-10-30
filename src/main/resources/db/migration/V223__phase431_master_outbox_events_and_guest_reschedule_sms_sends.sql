-- non-destructive
-- Reason: chk_outbox_event is re-added as a strict superset (every prior value kept, two new values); the unique index and CHECKs are on tables created in this file and constrain only rows written by this release.
-- Phase 431 (audit-fix cycle 1).
--
-- 1. Widen chk_outbox_event to admit the two performing-master rows. The master e-mail is its OWN
--    outbox row (own retry state), enqueued in the same transaction as the client/guest row, so a
--    master-mail failure can never block or repeat the guest SMS, and an SMS back-pressure re-queue
--    can never repeat the master mail. Both carry aggregate_id = booking id and the plain payload
--    {"actorUserId":"<uuid>"} (V32's chk_outbox_invite_payload already allows a payload on any
--    non-INVITE event).
--    History: V32 created it; V94 / V109 / V131 / V156 / V161 / V184 widened it to ten values.
--    Every prior value retained byte-for-byte; DROP ... IF EXISTS keeps a clean-DB replay deterministic.
--
-- 2. guest_reschedule_sms_sends — one row per guest reschedule SMS actually handed to the SMS pool.
--    Joined to bookings.guest_phone it feeds the per-phone budget shared with the walk-in
--    confirmation SMS (5 / rolling hour, silent: only the SMS is skipped). Stores NO phone and no
--    phone hash: the destination is bookings.guest_phone of booking_id. outbox_entry_id makes a
--    back-pressure retry of the same outbox row reuse its reservation instead of counting twice; it
--    has no FK because outbox rows are purged after 30 days while these rows matter for 1 h.
--
-- 3. guest_provider_reschedules — one row per PROVIDER reschedule of a guest booking/visit
--    (client_id IS NULL). Feeds the request-side cap (user decision 2026-10-30): at most 5 per
--    booking/visit per rolling 24 h, the 6th is rejected with RESCHEDULE_LIMIT_REACHED (409).
--    scope_id = the appointment id for a visit (a whole-visit move and a one-service move both count
--    against the visit), else the booking id. booking_id = the moved booking (the first item for a
--    whole-visit move), FK for cleanup only.
ALTER TABLE notification_outbox DROP CONSTRAINT IF EXISTS chk_outbox_event;

ALTER TABLE notification_outbox
    ADD CONSTRAINT chk_outbox_event CHECK (
        event_type IN ('NEW_BOOKING','STATUS_CHANGED','CLIENT_CANCELLED','INVITE',
                       'BOOKING_RESCHEDULED','REVIEW_REQUESTED','CLOSURE_REMINDER','SALON_CLOSED',
                       'MASTER_REMOVED','INAPP_PUSH',
                       'MASTER_BOOKING_RESCHEDULED','MASTER_BOOKING_DECLINED'));

CREATE TABLE guest_reschedule_sms_sends (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id      UUID        NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    outbox_entry_id UUID,
    sent_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- FK index (ON DELETE CASCADE from bookings).
CREATE INDEX idx_guest_reschedule_sms_sends_booking
    ON guest_reschedule_sms_sends (booking_id);

-- Per-phone budget (WHERE sent_at > ? joined to bookings by PK) and the retention purge.
CREATE INDEX idx_guest_reschedule_sms_sends_sent
    ON guest_reschedule_sms_sends (sent_at);

-- One reservation per outbox row (a back-pressure retry reuses it).
CREATE UNIQUE INDEX ux_guest_reschedule_sms_sends_outbox_entry
    ON guest_reschedule_sms_sends (outbox_entry_id)
    WHERE outbox_entry_id IS NOT NULL;

CREATE TABLE guest_provider_reschedules (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    scope_id       UUID        NOT NULL,
    booking_id     UUID        NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    rescheduled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The cap: WHERE scope_id = ? AND rescheduled_at > ?
CREATE INDEX idx_guest_provider_reschedules_scope_at
    ON guest_provider_reschedules (scope_id, rescheduled_at);

-- FK index (ON DELETE CASCADE from bookings) and the retention purge.
CREATE INDEX idx_guest_provider_reschedules_booking
    ON guest_provider_reschedules (booking_id);
CREATE INDEX idx_guest_provider_reschedules_at
    ON guest_provider_reschedules (rescheduled_at);
