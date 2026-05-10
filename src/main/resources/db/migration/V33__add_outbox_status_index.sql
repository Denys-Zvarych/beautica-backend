-- V33: add a full (non-partial) index on notification_outbox.status
--
-- Rationale
-- ---------
-- V32 created idx_outbox_pending as a PARTIAL index (WHERE status = 'PENDING') to
-- accelerate the drain-worker SELECT … FOR UPDATE SKIP LOCKED query.  That index is
-- intentionally narrow: rows with status IN ('PROCESSING', 'SENT', 'DEAD') are not
-- covered, so countByStatus() for non-PENDING statuses performs a sequential scan.
--
-- This full index covers:
--   • countByStatus('PROCESSING') — stuck-row detection by the health-check endpoint
--   • countByStatus('DEAD')       — DLQ depth monitoring
--   • countByStatus('SENT')       — housekeeping / archival queries
--
-- idx_outbox_pending (partial) is kept as-is; PostgreSQL's query planner chooses the
-- most selective index per query.  No index is dropped here.

CREATE INDEX IF NOT EXISTS idx_outbox_status ON notification_outbox (status);
