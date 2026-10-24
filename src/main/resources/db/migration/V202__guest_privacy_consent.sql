-- non-destructive
-- Reason: nullable columns added in this file; the CHECKs only constrain columns that are NULL on every existing row.
-- Phase 381 — guest privacy consent proof on LINK guest bookings (website BG-1).
--
-- Additive only: two nullable columns on bookings and on appointments, plus one pair CHECK per table
-- ("both NULL or both NOT NULL"). No backfill: legacy LINK rows stay NULL/NULL (the pair CHECK holds),
-- "new LINK rows carry consent" is enforced by GuestBookingService and its IT, not by the schema.
-- No index, no DROP.
--
-- Not PII: these columns record WHEN a guest accepted WHICH privacy-policy version, so they are
-- deliberately NOT touched by the phase 373 anonymisation / erase (they prove the lawful basis after
-- the guest's name and phone are gone).
--
-- Locking (as V199/V200): Flyway runs this file in ONE transaction; each ALTER takes ACCESS EXCLUSIVE
-- on its table until COMMIT. lock_timeout bounds the wait to ACQUIRE it (fail fast instead of queueing
-- behind a long transaction); statement_timeout bounds the CHECK scan (a metadata-only ADD COLUMN, then a
-- validation scan over columns that are NULL on every row).
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE bookings
    ADD COLUMN guest_privacy_accepted_at TIMESTAMPTZ,
    ADD COLUMN guest_privacy_policy_version VARCHAR(32);

ALTER TABLE bookings
    ADD CONSTRAINT ck_bookings_guest_privacy_pair
    CHECK ((guest_privacy_accepted_at IS NULL) = (guest_privacy_policy_version IS NULL));

ALTER TABLE appointments
    ADD COLUMN guest_privacy_accepted_at TIMESTAMPTZ,
    ADD COLUMN guest_privacy_policy_version VARCHAR(32);

ALTER TABLE appointments
    ADD CONSTRAINT ck_appointments_guest_privacy_pair
    CHECK ((guest_privacy_accepted_at IS NULL) = (guest_privacy_policy_version IS NULL));
