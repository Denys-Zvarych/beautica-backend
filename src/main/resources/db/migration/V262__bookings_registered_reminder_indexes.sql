-- non-destructive
-- Reason: index-only change — two new partial indexes, four superseded partial indexes dropped; no column, row or
-- constraint touched. Data is unaffected; the previous app's finders run unchanged (only their plan differs).
--
-- V262 — Phase 527 follow-up (B527): index-back the registered reminder sweeps' OR predicate.
--
-- WHY
-- B527 widened BookingRepository.findRegisteredBookingsForReminder / findRegisteredBookingsForTwoHourReminder to
--   (booking_source = 'APP' OR client_link_reason IS NOT NULL) AND status = 'CONFIRMED' AND <flag> = FALSE
--   AND client_id IS NOT NULL AND starts_at <window>
-- V260 expected a BitmapOr of V240's idx_bookings_reminder_app / _2h_app with its own _attached twins. The planner
-- does not take it: no single one of those four partial indexes is implied by the OR, and EXPLAIN on Testcontainers
-- (PG 18) chose an Index Scan on idx_bookings_client_slot_overlap (client_id, starts_at, ends_at; skip-scan on
-- starts_at) with the OR + flag left as a heap Filter — i.e. every CONFIRMED registered booking in the window is
-- visited, reminded or not.
--
-- SHAPE — one partial index per sweep whose predicate is the query's own WHERE minus the window (Anti-Bug §E-5 /
-- §O-6), so the planner proves the implication directly, the window is the Index Cond and no Filter remains:
--   (starts_at) WHERE (booking_source = 'APP' OR client_link_reason IS NOT NULL) AND status = 'CONFIRMED'
--                     AND <flag> = FALSE AND client_id IS NOT NULL
-- The row set is exactly the union of the V240 and V260 indexes it replaces (an APP row always has client_id —
-- chk_bookings_guest_fields; an attached row always has client_id — chk_bookings_client_link + the V260 arm), so
-- those four are now pure write amplification and are dropped in the same transaction (§O-6). No other query reads
-- them: the guest sweeps use idx_bookings_reminder / _2h (LINK | STAFF, V227), untouched here.
--
-- LOCKING — one Flyway transaction (house pattern: no CONCURRENTLY, V138's guard rationale). CREATE INDEX takes
-- SHARE, DROP INDEX ACCESS EXCLUSIVE (catalog-only once granted) on bookings. Both new indexes are partial over
-- un-reminded CONFIRMED registered rows only, so the builds are small. lock_timeout bounds each lock wait,
-- statement_timeout each build (same values as V227 / V260). A timeout rolls the whole migration back cleanly.
--
-- Immutable-migration rule (§O-9): V240 / V260 are not edited; this is the next free version (max existing = V261).
-- Rollback image: the previous app's finders are planned on the new indexes too (same predicate text).
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

CREATE INDEX IF NOT EXISTS idx_bookings_reminder_registered ON bookings (starts_at)
    WHERE (booking_source = 'APP' OR client_link_reason IS NOT NULL)
      AND status = 'CONFIRMED' AND reminder_sent = FALSE AND client_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_bookings_reminder_2h_registered ON bookings (starts_at)
    WHERE (booking_source = 'APP' OR client_link_reason IS NOT NULL)
      AND status = 'CONFIRMED' AND reminder_2h_sent = FALSE AND client_id IS NOT NULL;

DROP INDEX IF EXISTS idx_bookings_reminder_app;
DROP INDEX IF EXISTS idx_bookings_reminder_2h_app;
DROP INDEX IF EXISTS idx_bookings_reminder_attached;
DROP INDEX IF EXISTS idx_bookings_reminder_2h_attached;
