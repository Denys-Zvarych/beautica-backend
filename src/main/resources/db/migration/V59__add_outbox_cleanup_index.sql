-- Fix MEDIUM-8 PERF: supports efficient TTL cleanup of terminal outbox rows (SENT/DEAD
-- older than 30 days). The partial index pre-filters on the status predicate so the
-- DELETE in NotificationOutboxDrainWorker.purgeStaleOutboxRows() can use an index scan
-- on updated_at rather than a sequential scan of the full notification_outbox table.
--
-- Aggregate row count without cleanup: ~100 bookings/day × 3 notifications/booking
-- × 365 days/year = ~109,500 rows/year. With the 30-day retention window the steady-
-- state table size stays bounded at ~9,000 rows regardless of platform scale.
CREATE INDEX IF NOT EXISTS idx_outbox_terminal_updated
    ON notification_outbox (updated_at)
    WHERE status IN ('SENT', 'DEAD');
