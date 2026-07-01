-- Phase 13.4 fix-forward — relax chk_bookings_guest_fields for the guest cancel path.
--
-- BUG: V89 made the LINK branch require `cancel_token IS NOT NULL` unconditionally.
-- The public guest-cancellation UPDATE (BookingRepository.consumeCancelToken) atomically
-- sets status = 'CANCELLED' AND cancel_token = NULL on the LINK row. That mutation
-- violates V89's CHECK, so PostgreSQL raises a constraint violation and *every* real
-- guest cancellation 500s. V89 is immutable (already shipped), so we fix-forward.
--
-- FIX: a LINK row may carry a NULL cancel_token once it reaches a terminal / non-active
-- status — i.e. when an active cancellation link no longer applies. Every other LINK
-- clause from V89 is preserved verbatim (guest_name + guest_phone required; guest_surname
-- stays OPTIONAL — Booking.guestBooking(...) and its Javadoc treat it as nullable, so
-- forcing NOT NULL here would 500 legitimate surname-less guest bookings). The APP branch
-- is kept byte-for-byte. Only the cancel_token clause is relaxed.
--
-- Terminal-state set: CANCELLED is the state the cancel path actually writes (it nulls
-- the token). COMPLETED / NOT_COMPLETED / DECLINED are included for robustness — a LINK
-- booking that has finished or expired should not be forced to retain a live cancel token.
-- The active states PENDING / CONFIRMED still require a non-null token, so a create-time
-- LINK booking with a null token is still rejected exactly as V89 intended.
-- status is persisted @Enumerated(EnumType.STRING), so these literals mirror V89's style.
ALTER TABLE bookings DROP CONSTRAINT chk_bookings_guest_fields;

ALTER TABLE bookings
    ADD CONSTRAINT chk_bookings_guest_fields CHECK (
        (booking_source = 'LINK'
            AND client_id IS NULL
            AND guest_name IS NOT NULL
            AND guest_phone IS NOT NULL
            AND (cancel_token IS NOT NULL
                 OR status IN ('CANCELLED', 'COMPLETED', 'NOT_COMPLETED', 'DECLINED')))
        OR
        (booking_source = 'APP'
            AND client_id IS NOT NULL
            AND guest_name IS NULL
            AND guest_surname IS NULL
            AND guest_phone IS NULL
            AND cancel_token IS NULL)
    );
