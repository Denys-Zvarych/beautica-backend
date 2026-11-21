-- non-destructive
-- Reason: validates four constraints V260 added NOT VALID (no row can violate them) and backfills the V260 columns; the only data narrowed is the guest cancel token (hash + sealed) of rows that become the account's own, by design (an old SMS link must not cancel an account booking); a re-run changes 0 rows.
-- Phase 527 — attach existing guest (LINK) and walk-in (STAFF) bookings to the CLIENT account whose VERIFIED phone
-- equals the booking phone (D1). Same predicate as ClientPhoneAttachService (B527 scope 1):
--   rows   = client_id IS NULL, guest_phone set, not anonymised (GST-51), not detached (CLI-24), source LINK|STAFF,
--            any status
--   target = users.role = 'CLIENT' AND is_active AND phone_verified_at IS NOT NULL AND phone_number = guest_phone
-- On attach: client_id, client_linked_at = now(), client_link_reason = 'BACKFILL', guest_phone = NULL. Name, surname,
-- consent columns and notes are kept; the cancel token (hash + sealed) is NULLed so an old SMS link cannot cancel the account's booking. Visit headers first, then rows (a header and its rows always
-- share one phone). No notifications are written: the bookings simply appear in the client's list (B527 scope 3).
--
-- IDEMPOTENT: an attached row has guest_phone NULL and client_id set, so neither UPDATE selects it again. Replaying
-- on a clean DB is deterministic: the match is by the E.164 business key, no generated id takes part.
--
-- LOCKING — Flyway runs this whole file as ONE transaction: the SHARE UPDATE EXCLUSIVE lock the four VALIDATEs take on
-- bookings / appointments is held THROUGH the backfill UPDATEs below (reads and writes continue; only concurrent DDL /
-- VACUUM wait), plus row locks on the matched rows (the guest-phone partial indexes serve the join). lock_timeout / statement_timeout bound the wait and the work, as in V138 / V226.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

-- ── 0. validate V260's four NOT VALID CHECKs (SHARE UPDATE EXCLUSIVE: reads and writes continue) ─────────────
ALTER TABLE bookings     VALIDATE CONSTRAINT chk_bookings_client_link;
ALTER TABLE appointments VALIDATE CONSTRAINT chk_appointments_client_link;
ALTER TABLE bookings     VALIDATE CONSTRAINT chk_bookings_guest_fields;
ALTER TABLE appointments VALIDATE CONSTRAINT chk_appointment_guest_fields;

UPDATE appointments a
   SET client_id = u.id,
       client_linked_at = now(),
       client_link_reason = 'BACKFILL',
       guest_phone = NULL,
       cancel_token_hash = NULL,
       cancel_token_sealed = NULL,
       updated_at = now()
  FROM users u
 WHERE a.client_id IS NULL
   AND a.guest_phone IS NOT NULL
   AND a.guest_pii_anonymised_at IS NULL
   AND a.client_detached_at IS NULL
   AND a.booking_source IN ('LINK', 'STAFF')
   AND u.role = 'CLIENT'
   AND u.is_active
   AND u.phone_verified_at IS NOT NULL
   AND u.phone_number = a.guest_phone;

UPDATE bookings b
   SET client_id = u.id,
       client_linked_at = now(),
       client_link_reason = 'BACKFILL',
       guest_phone = NULL,
       cancel_token_hash = NULL,
       cancel_token_sealed = NULL,
       updated_at = now()
  FROM users u
 WHERE b.client_id IS NULL
   AND b.guest_phone IS NOT NULL
   AND b.guest_pii_anonymised_at IS NULL
   AND b.client_detached_at IS NULL
   AND b.booking_source IN ('LINK', 'STAFF')
   AND u.role = 'CLIENT'
   AND u.is_active
   AND u.phone_verified_at IS NOT NULL
   AND u.phone_number = b.guest_phone;
