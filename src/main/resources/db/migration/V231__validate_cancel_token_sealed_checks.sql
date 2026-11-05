-- non-destructive
-- Reason: only VALIDATEs the four CHECKs V230 added NOT VALID; every existing row already satisfies them (cancel_token_sealed was added NULL in V230 and every write since is checked), so no row is changed or rejected.
-- Phase 435 audit-fix — the validation scan for V230's cancel_token_sealed CHECKs, in its OWN Flyway transaction.
-- VALIDATE CONSTRAINT takes SHARE UPDATE EXCLUSIVE: reads and writes on bookings/appointments keep running during the
-- scan (V230's ADD ... NOT VALID held ACCESS EXCLUSIVE only for the catalog change, with no scan).
SET LOCAL lock_timeout = '3s';

ALTER TABLE bookings     VALIDATE CONSTRAINT chk_bookings_cancel_token_sealed_format;
ALTER TABLE bookings     VALIDATE CONSTRAINT chk_bookings_cancel_token_sealed_pair;
ALTER TABLE appointments VALIDATE CONSTRAINT chk_appointments_cancel_token_sealed_format;
ALTER TABLE appointments VALIDATE CONSTRAINT chk_appointments_cancel_token_sealed_pair;
