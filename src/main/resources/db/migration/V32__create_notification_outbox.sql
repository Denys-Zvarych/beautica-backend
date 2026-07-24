CREATE TABLE notification_outbox (
    id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type   VARCHAR(50)   NOT NULL,
    aggregate_id UUID,
    payload      VARCHAR(4000),
    status       VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    attempts     INTEGER       NOT NULL DEFAULT 0,
    last_error   VARCHAR(500),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING','PROCESSING','SENT','DEAD')),
    CONSTRAINT chk_outbox_event  CHECK (event_type IN ('NEW_BOOKING','STATUS_CHANGED','CLIENT_CANCELLED','INVITE')),
    CONSTRAINT chk_outbox_invite_payload
        CHECK (event_type != 'INVITE' OR payload IS NOT NULL),
    CONSTRAINT chk_outbox_booking_aggregate
        CHECK (event_type = 'INVITE' OR aggregate_id IS NOT NULL)
);
-- Partial index: only undelivered rows need fast lookup; predicate matches drain query exactly
CREATE INDEX idx_outbox_pending ON notification_outbox(created_at)
    WHERE status = 'PENDING';
