-- non-destructive
-- Reason: review_invites was created in V213 of this same unreleased track and is empty in every environment, so
-- relaxing appointment_id NOT NULL, adding booking_id and the new CHECK needs no backfill and no NOT VALID step.
-- Phase 400 D7 — an invite's subject is a visit (appointment_id) OR a single LINK booking without a visit header
-- (booking_id, bookings.appointment_id IS NULL). Exactly one is set (chk_review_invites_one_subject).
-- ux_review_invites_booking is the ON CONFLICT target for booking-keyed inserts and serves the lookups; NULL
-- booking_ids are distinct, so appointment-keyed rows are unaffected. ON DELETE CASCADE mirrors appointment_id;
-- no external-storage column, so no pre-CASCADE sweep is needed (§O-8 does not apply).
ALTER TABLE review_invites ALTER COLUMN appointment_id DROP NOT NULL;

ALTER TABLE review_invites ADD COLUMN booking_id UUID NULL REFERENCES bookings(id) ON DELETE CASCADE;

ALTER TABLE review_invites ADD CONSTRAINT ux_review_invites_booking UNIQUE (booking_id);

ALTER TABLE review_invites ADD CONSTRAINT chk_review_invites_one_subject
    CHECK (num_nonnulls(appointment_id, booking_id) = 1);

COMMENT ON COLUMN review_invites.booking_id IS
    'Phase 400 D7. Set only for a single LINK booking (bookings.appointment_id IS NULL); exactly one of '
    'appointment_id / booking_id is set (chk_review_invites_one_subject).';
