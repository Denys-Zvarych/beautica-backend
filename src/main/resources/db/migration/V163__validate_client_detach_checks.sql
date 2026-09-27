-- VALIDATE takes only SHARE UPDATE EXCLUSIVE, so this file does NOT block reads or writes; it is
-- split out of V162 for exactly that reason. Precedent: V139 added chk_appointment_guest_fields
-- NOT VALID and V140:29 validated it separately.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE bookings     VALIDATE CONSTRAINT chk_bookings_guest_fields;
ALTER TABLE appointments VALIDATE CONSTRAINT chk_appointment_guest_fields;
