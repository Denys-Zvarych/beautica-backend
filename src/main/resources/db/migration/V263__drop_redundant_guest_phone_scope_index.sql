-- non-destructive
-- Reason: drops one partial index whose row set is identical to an existing one; no column, row or constraint
-- touched, every reader keeps an index path (idx_bookings_guest_phone_open, V199).
--
-- V263 — Phase 508 / 527 follow-up: one guest-phone index, not two.
--
-- WHY
-- V259 added idx_bookings_guest_phone_starts_at ON bookings (guest_phone) WHERE client_id IS NULL AND guest_phone IS
-- NOT NULL for the provider client card's guest scope. V199 already has idx_bookings_guest_phone_open ON bookings
-- (guest_phone) WHERE client_id IS NULL AND guest_pii_anonymised_at IS NULL AND client_detached_at IS NULL. Under the
-- validated chk_bookings_guest_fields (V260/V261) the two hold the SAME rows: every anonymised arm and the detached arm
-- force guest_phone IS NULL, and every non-anonymised, non-detached client_id IS NULL arm (LINK, STAFF walk-in) forces
-- guest_phone IS NOT NULL. Same column, same rows — so on every guest_phone lookup the planner faced an exact tie and
-- picked the V259 copy, and every guest write paid for both.
--
-- FIX — keep V199's index (the retention / erase-by-phone / B527 attach queries already match its predicate
-- verbatim) and drop V259's. The one reader that matched only V259 — ClientCardSql's guest scope — now states
-- client_detached_at IS NULL as well (a no-op filter by the CHECK above: a detached row has no phone), so it implies
-- V199's predicate and keeps its index read. Both plans are pinned by V263GuestPhoneIndexMigrationTest.
--
-- LOCKING — DROP INDEX takes ACCESS EXCLUSIVE on bookings but is catalog-only once granted; lock_timeout bounds the
-- wait (V138 rationale). Immutable-migration rule (§O-9): V259 is not edited; next free version (max = V262).
-- Rollback image: index-only change — the previous app's statements return the same rows; only the previous
-- ClientCardSql guest scope (no detached term) loses its phone index and reads through its salon / master scope.
SET LOCAL lock_timeout = '3s';

DROP INDEX IF EXISTS idx_bookings_guest_phone_starts_at;
