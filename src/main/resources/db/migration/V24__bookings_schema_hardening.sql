-- Drop idx_bookings_client_id: subsumed by composite idx_bookings_client_status_starts_at
-- (leading column is client_id, so all queries filtering by client_id alone still use it)
DROP INDEX IF EXISTS idx_bookings_client_id;

-- Ensure cancellation_reason is only set on terminal-status rows
ALTER TABLE bookings
    ADD CONSTRAINT chk_cancellation_reason_status
    CHECK (
        cancellation_reason IS NULL
        OR status IN ('DECLINED', 'CANCELLED', 'NOT_COMPLETED')
    );
