-- V271 — partial indexes for the «toRateClient» leg of the pending-actions count, both guest-flag states.
--
-- WHY
-- The count (PendingBookingActionsService -> BookingClosureRule#awaitingProviderClientReview) now takes its
-- client-identity conjunct from ClientRatingEligibility#rateable(), the same rule as the
-- providerCanReviewClient CTA (badge-vs-CTA drift fix). It emits, per scope (master_id = ? or salon_id = ?):
--
--   flag off: status = 'COMPLETED' AND client_id IS NOT NULL                               AND NOT EXISTS (client_reviews ...)
--   flag on : status = 'COMPLETED' AND (client_id IS NOT NULL OR guest_phone IS NOT NULL)      AND NOT EXISTS (...)
--
-- (rateable() omits the in-memory gate's guest_pii_anonymised_at IS NULL term: chk_bookings_guest_fields, V260,
-- forces guest_phone NULL on every anonymised row, so the flag-on form EQUALS this index's predicate and the count
-- runs as an index-only scan.)
--
-- V191's idx_bookings_{master,salon}_completed_client carry `client_id IS NOT NULL` only; the flag-on OR arm
-- does not imply that predicate, so with APP_GUEST_CLIENT_RATING_ENABLED=true the planner cannot use them and
-- every COMPLETED row of the scope costs a heap fetch.
--
-- SHAPE
--   * Predicate `status = 'COMPLETED' AND (client_id IS NOT NULL OR guest_phone IS NOT NULL)` is implied by BOTH
--     query forms (flag off: `client_id IS NOT NULL` implies the OR; flag on: identical), so the planner can prove
--     applicability for either flag value.
--   * Same key / INCLUDE (id) as V191 (count needs no order; INCLUDE keeps the NOT EXISTS anti-join probe on
--     uq_client_reviews_booking index-only), one index per scope.
--   * V191's two indexes are KEPT, not dropped: with the flag off (the prod default until W051, SUP-33) they are
--     the smaller, exact-match choice (no guest rows) and remain what the planner picks. Drop them in a later
--     migration once the guest flag is permanently on. V271BookingsCompletedRateableIndexesMigrationTest pins
--     that V191 cannot serve the flag-on form and V271 can.
--
-- Not expressed on the entity beyond key columns: JPA's @Index cannot declare a partial predicate or INCLUDE
-- (§E-6, same convention as V191 on Booking's @Table).
--
-- Immutable-migration rule (Anti-Bug §O-9): new, fix-forward version (max existing = V270).

-- ── Lock + statement guards (V138's block and rationale, same values as V118/V119/V121/V128/V137/V191) ──
-- Plain CREATE INDEX (not CONCURRENTLY — Flyway wraps each migration in one transaction) takes SHARE on
-- `bookings`, blocking writes for the build: fail fast on the lock wait (5s) and bound the held-lock build
-- (statement_timeout is PER statement, re-armed for each of the two builds below, so 1min each). SET LOCAL
-- scopes both to this migration's transaction.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '1min';

CREATE INDEX IF NOT EXISTS idx_bookings_master_completed_rateable
    ON bookings (master_id) INCLUDE (id)
    WHERE status = 'COMPLETED' AND (client_id IS NOT NULL OR guest_phone IS NOT NULL);

CREATE INDEX IF NOT EXISTS idx_bookings_salon_completed_rateable
    ON bookings (salon_id) INCLUDE (id)
    WHERE status = 'COMPLETED' AND (client_id IS NOT NULL OR guest_phone IS NOT NULL);
