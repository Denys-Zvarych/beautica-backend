-- Client-side booking-conflict validation: a client must not be able to create, or
-- reschedule into, a window that overlaps a PENDING/CONFIRMED booking they already hold
-- with ANY master/salon. BookingRepository.findFirstConflictingClientBookingId[Excluding]
-- filters `client_id = ? AND status IN ('PENDING','CONFIRMED') AND starts_at < ? AND
-- ends_at > ?` — the pre-existing idx_bookings_client_starts_at (client_id, starts_at) does
-- not cover the status predicate nor ends_at, forcing a heap filter over the client's full
-- history. This partial index mirrors idx_bookings_master_slot_overlap (V26), the equivalent
-- master-scoped overlap index, keyed by client_id instead of master_id.
--
-- client_id IS NOT NULL is part of the predicate (not just an implicit consequence of the
-- status filter): a guest (LINK) booking always has client_id = NULL per
-- chk_bookings_guest_fields (V89) and can never satisfy the query's `client_id = :clientId`
-- equality, so indexing those rows is pure write amplification on every guest-booking
-- insert for zero read benefit. This migration has never been committed/deployed, so the
-- predicate is fixed here in place rather than patched by a follow-up V-file.
CREATE INDEX idx_bookings_client_slot_overlap
    ON bookings (client_id, starts_at, ends_at)
    WHERE status IN ('PENDING', 'CONFIRMED') AND client_id IS NOT NULL;
