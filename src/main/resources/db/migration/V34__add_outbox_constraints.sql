-- Index for rescue query: find stuck PROCESSING rows from pods that crashed mid-drain
CREATE INDEX idx_outbox_stuck ON notification_outbox(updated_at) WHERE status = 'PROCESSING';

-- Prevent negative attempt counter which would hide DEAD rows from DLQ sweep
ALTER TABLE notification_outbox
    ADD CONSTRAINT chk_outbox_attempts CHECK (attempts >= 0);
