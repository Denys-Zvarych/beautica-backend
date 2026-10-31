-- Phase 412 audit-fix (perf LOW): V226 widened the two reminder partial indexes to LINK | STAFF, so every
-- STAFF row — registered-client walk-ins (guest_phone NULL) included — entered them and none of those is ever
-- reminded. Both sweeps now also filter guest_phone IS NOT NULL at the top level
-- (BookingRepository#findGuestBookingsForReminder / #findGuestBookingsForTwoHourReminder), so the index
-- predicates add it too and stop indexing rows no sweep can select. V226 is applied and immutable; fixed forward.
-- Plain (non-unique) indexes only: the previous image's sweep (LINK, guest_phone NOT NULL by CHECK) still
-- implies the new predicates.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

DROP INDEX idx_bookings_reminder;
CREATE INDEX idx_bookings_reminder ON bookings (starts_at)
    WHERE booking_source IN ('LINK', 'STAFF') AND guest_phone IS NOT NULL AND reminder_sent = FALSE;

DROP INDEX idx_bookings_reminder_2h;
CREATE INDEX idx_bookings_reminder_2h ON bookings (starts_at)
    WHERE booking_source IN ('LINK', 'STAFF') AND guest_phone IS NOT NULL AND status = 'CONFIRMED'
      AND reminder_2h_sent = FALSE;
