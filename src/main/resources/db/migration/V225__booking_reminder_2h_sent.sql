-- Phase 413 — 2h-before guest reminder claim flag. Mirrors reminder_sent (V89): flipped by the conditional
-- UPDATE ... RETURNING claim in BookingReminderJob#sweepTwoHour, so two overlapping instances remind once.
-- Constant DEFAULT → metadata-only ADD COLUMN (no table rewrite).
ALTER TABLE bookings
    ADD COLUMN reminder_2h_sent BOOLEAN NOT NULL DEFAULT FALSE;

-- Partial index mirroring the sweep predicate exactly (LINK + CONFIRMED + not yet 2h-reminded, by starts_at), so
-- cancelled / declined rows never enter it.
CREATE INDEX idx_bookings_reminder_2h ON bookings (starts_at)
    WHERE booking_source = 'LINK' AND status = 'CONFIRMED' AND reminder_2h_sent = FALSE;
