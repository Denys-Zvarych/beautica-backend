-- Phase 26.6 — indices & limit hardening for the "Мої записи" (GET /bookings/me) filter/sort
-- surface built by Phases 26.1-26.5. Every index below was chosen — and every candidate NOT
-- added here was rejected — from EXPLAIN (ANALYZE, BUFFERS) evidence against a seeded
-- 50,000-row single-master dataset (scripts/seed-booking-perf-dataset.sh), per the phase's
-- locked "measure before indexing" discipline. Full before/after EXPLAIN output for all 6
-- named shapes + the booked-days aggregation lives in the phase 26.6 MR, not duplicated here.
--
-- CREATE INDEX CONCURRENTLY vs plain: PLAIN, matching every prior bookings-table index
-- migration in this codebase (V18, V19, V22, V43, V93, V95, V112 all use plain CREATE INDEX /
-- CREATE INDEX IF NOT EXISTS) — established project convention for this pre-launch table, not
-- a guess. Apply during a low-traffic deploy window as routine hygiene regardless.

-- ── Shapes 1/3/4 (default sort, day-rail tap, date range), master + client scope ──────────
-- idx_bookings_master_starts_at already existed (V18) as (master_id, starts_at), no trailing
-- id. That 2-column form already avoided a sequential scan on all three shapes (Index Scan
-- Backward) — do NOT re-create a duplicate of the base 2-column form, per the phase doc's
-- explicit warning. But a deep OFFSET near the page-cap boundary (~20,000 rows in) still forced
-- an Incremental Sort node to resolve the mandatory `id ASC` tiebreaker (Phase 26.3): 4.85ms
-- measured. Adding `id` as a third column turns this into a pure Index Only Scan with NO sort
-- node at ANY offset — 3.28ms at the same deep offset (32% faster) AND 0.07ms at page 0 (also
-- faster than the 2-column baseline's 0.14ms — satisfies "Shape 1 not slower than baseline").
-- The 3-column index is a strict superset of the 2-column one (same leading columns), so the
-- old index is now fully redundant and is dropped in the same migration rather than left
-- alongside its superset (pure write-amplification for zero remaining benefit).
DROP INDEX IF EXISTS idx_bookings_master_starts_at;
CREATE INDEX idx_bookings_master_starts_at
    ON bookings (master_id, starts_at DESC, id ASC);

-- Client-scope equivalent (idx_bookings_client_starts_at, V95) — identical trailing-id
-- justification, measured against the same seeded dataset (all 50,000 seeded rows share one
-- client_id, giving the client path the same-scale measurement as the master path for free).
DROP INDEX IF EXISTS idx_bookings_client_starts_at;
CREATE INDEX idx_bookings_client_starts_at
    ON bookings (client_id, starts_at DESC, id ASC);

-- ── Shape 5 (sort=priceAtBooking,desc, unfiltered) — the documented worst case ────────────
-- No index covered price_at_booking at all before this migration. EXPLAIN showed a Sequential
-- Scan of the master's entire history + a top-N heapsort even at page 0 (9.28ms — the ONLY
-- shape in this phase's whole measurement pass that produced a seq scan) and it scaled worse at
-- a deep offset (35.99ms). This index makes it an Index Only Scan instead: 0.06ms at page 0
-- (~155x) and 2.24ms at the same deep offset (~16x). Closes backlog Performance finding (Phase
-- 26.3 F3, backend-perf).
CREATE INDEX idx_bookings_master_price_id
    ON bookings (master_id, price_at_booking DESC, id ASC);

CREATE INDEX idx_bookings_client_price_id
    ON bookings (client_id, price_at_booking DESC, id ASC);

-- ── Shape 6 variant: serviceId + status, NO date range (rare single service) ──────────────
-- Phase 26.4 F3 (backlog): master_service_id IN (...) applies as a post-scan Filter on
-- idx_bookings_master_starts_at. At the seeded 50,000-row scale, the COMBINED shape from the
-- phase doc (service + status + a real date range) stayed cheap via that same starts_at index
-- (the range predicate alone was already selective — NOT re-indexed here, no duplicate). The
-- degenerate case IS real: service + status with NO date range narrowing (browsing one
-- specific, rarely-booked service across the master's whole history) fell onto an unrelated
-- index (idx_bookings_master_slot_overlap) and burned 10,000 filtered rows / 4.34ms to return
-- zero matches — exactly the "loses early-LIMIT termination, scales with total row count"
-- pattern the backlog finding describes. This composite index turns it into a direct
-- index-range seek on (master_id, master_service_id): 1.72ms for the same query (~2.5x),
-- fewer buffer reads.
--
-- Salon-scope equivalent (backlog Finding 146 also names salon_id) is deliberately NOT added
-- here: no salon-scale (50,000+ row) dataset was measured this phase, and this phase's own
-- "measure before indexing" rule forbids blind-copying the master finding onto salon scope
-- without evidence. Flagged as a follow-up for a salon-scale measurement pass.
CREATE INDEX idx_bookings_master_service_starts_at
    ON bookings (master_id, master_service_id, starts_at DESC);

-- ── Rejected candidates (measured, deliberately NOT added) ────────────────────────────────
-- * status column index: Shape 2 (status IN (...)) already filters cheaply off
--   idx_bookings_master_starts_at (0.068ms, 30 rows removed by filter at 50,000-row scale) —
--   exactly the "5 values, low cardinality, cheaper to filter than to index" case the phase
--   doc predicted. Not indexed; do not add "for symmetry" with the other predicates.
-- * Booked-days expression index: a TESTED DEAD END per the 26.5 audit (the WHERE clause
--   filters on raw starts_at, not the AT TIME ZONE expression, so an expression index could
--   never be chosen as an Index Cond) — not re-attempted here. Re-measured instead at the
--   50,000-row scale: 5.21ms for a 361-day window (Index Only Scan on
--   idx_bookings_master_starts_at feeding a Sort/Unique over ~16,245 in-range rows, reduced to
--   361 distinct dates) — comfortably inside the sub-10ms budget the phase doc set. No index
--   change for this path.
-- * Client-scope (client_id, master_service_id, starts_at) composite: not requested by the
--   routed backlog finding (Finding 146 names master_id/salon_id only) and out of scope for
--   this phase — findClientBookingDetails's sentinel-predicate rewrite is Phase 26.7's, not
--   26.6's, and this phase does not change that query's shape.
