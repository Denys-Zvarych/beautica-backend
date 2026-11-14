-- V239 — Phase 493 (GST-77): bookings.reminder_2h_rearmed, the explicit 2 h re-arm marker.
--
-- WHY
-- The 2 h VISIT claim (BookingRepository.claimTwoHourVisitRemindersByAppointmentIdsNoLockTimeout) skips a visit
-- once any item has reminder_2h_sent = TRUE — phase 413's rule that a service ADDED to an already-reminded visit
-- gets no second reminder. A per-item MOVE (BOOK-14) leaves the same shape (one row FALSE, siblings TRUE), so the
-- moved item was never reminded about its new time. Booking.reschedule now sets this marker on the moved row only;
-- the claim lets a re-armed row bypass the sibling guard and clears the marker when it claims the row.
--
-- SAFETY
-- Expand-only: ADD COLUMN ... NOT NULL DEFAULT FALSE is metadata-only on PG 11+ (no table rewrite, no backfill).
-- The previous app version neither reads nor writes the column; its INSERTs get the DEFAULT. Existing partial
-- indexes (V225 / V226 / V227) are untouched — their predicates do not reference the new column.
-- Rollback image: the column is ignored by the previous app; drop only via a later contract migration.
SET LOCAL lock_timeout = '5s';

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS reminder_2h_rearmed BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN bookings.reminder_2h_rearmed IS
    'Phase 493: TRUE after a reschedule moved starts_at; lets the 2h visit claim bypass the reminded-sibling guard for this row only. Cleared by the claim.';
