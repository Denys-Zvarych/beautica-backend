-- Track 25.x — booking notes. Adds the client's own cancellation note (fixes D2: the client's
-- /cancel comment was previously validated at the API but never persisted) and pins the
-- provider_comment / client_cancellation_note write-path invariants with CHECK constraints.
--
-- STATEMENT ORDER MATTERS (this repo has a Flyway crash-loop history — see V113 for the same
-- discipline). Any UPDATE that could touch existing rows runs FIRST, while no new CHECK exists
-- yet to reject it; only once the backfill has proven every row already satisfies the invariant
-- do we add the CHECK. Reversing this order risks a crash-loop on deploy if prod data (which
-- this migration cannot see ahead of time) ever violated the new predicate.
--
-- Both new CHECK constraints below are added NOT VALID here and VALIDATEd in V115, NOT in this
-- same file. MEDIUM finding fixed here (residual, second pass): this migration's first draft, and
-- V113 before it, both paired ADD CONSTRAINT ... NOT VALID with VALIDATE CONSTRAINT in the SAME
-- migration file on the theory that it avoids a full-table scan under ACCESS EXCLUSIVE — that is
-- COSMETIC and achieves nothing. Flyway runs one whole migration script inside a single
-- transaction by default (`mixed=false`; no `spring.flyway.mixed` / executeInTransaction=false
-- configured anywhere in this repo), and Postgres never downgrades a lock mid-transaction, so a
-- VALIDATE CONSTRAINT appended right after its own ADD CONSTRAINT still runs its full-table scan
-- while that same transaction is still holding the ACCESS EXCLUSIVE lock ADD CONSTRAINT took —
-- identical severity/duration to a single validating ADD CONSTRAINT against `bookings`, the
-- hottest write table in the system. The lock-avoidance idiom only works split across a
-- transaction boundary, i.e. a separate Flyway version: V115 VALIDATEs both constraints added
-- here (and the one V113 added) after this migration has committed and released its ACCESS
-- EXCLUSIVE lock, so V115's scan runs under the lighter SHARE UPDATE EXCLUSIVE lock instead.
--
-- This split changes NOTHING about enforcement: ADD CONSTRAINT ... NOT VALID is metadata-only
-- (instant) and enforces the CHECK on every NEW insert/update from the moment IT commits (i.e.
-- from this migration, not from V115) — the backfill in step 2 below already guarantees every
-- EXISTING row satisfies it, so there is no window, however brief, where the invariant is
-- unenforced. V115 only proves retroactively that pre-existing rows also comply.

-- 1) New column: the client's own cancellation note (Phase 25.4). Written ONLY by
-- BookingService.cancelBooking (CONFIRMED -> CANCELLED).
ALTER TABLE bookings ADD COLUMN client_cancellation_note VARCHAR(1000);

-- 2) Backfill FIRST. provider_comment has, in practice, only ever been written by
-- declineBooking/notCompleteBooking (both CONFIRMED -> DECLINED/NOT_COMPLETED transitions), so
-- this should touch zero rows — but it runs unconditionally so step 3's CHECK can never
-- crash-loop Flyway against data this migration cannot see ahead of time.
UPDATE bookings
   SET provider_comment = NULL
 WHERE provider_comment IS NOT NULL
   AND status NOT IN ('DECLINED', 'NOT_COMPLETED');

-- 3) provider_comment is written ONLY by declineBooking/notCompleteBooking — pin that invariant.
-- ADD CONSTRAINT ... NOT VALID is metadata-only (instant) and enforces the CHECK on every NEW
-- insert/update from the moment THIS statement commits — the backfill in step 2 above already
-- guarantees every EXISTING row satisfies it. VALIDATE CONSTRAINT chk_provider_comment_status
-- runs in V115 (separate transaction — see the header comment above for why this must NOT be a
-- same-file VALIDATE CONSTRAINT).
ALTER TABLE bookings
    ADD CONSTRAINT chk_provider_comment_status
    CHECK (provider_comment IS NULL OR status IN ('DECLINED', 'NOT_COMPLETED'))
    NOT VALID;

-- 4) client_cancellation_note is written ONLY by cancelBooking (CONFIRMED -> CANCELLED). No
-- backfill needed — the column is brand new in this same migration, so it is NULL on every
-- pre-existing row and trivially satisfies the CHECK. VALIDATE CONSTRAINT
-- chk_client_cancellation_note_status also runs in V115, for the same reason as step 3.
ALTER TABLE bookings
    ADD CONSTRAINT chk_client_cancellation_note_status
    CHECK (client_cancellation_note IS NULL OR status = 'CANCELLED')
    NOT VALID;

-- Deliberately NO constraint on client_comment. Unlike the two notes above, client_comment is
-- the booking-CREATION note (set once from CreateBookingRequest.clientComment at POST /bookings)
-- and legitimately exists on CONFIRMED/COMPLETED rows, not just terminal ones. A naive sibling
-- CHECK mirroring the two above would FAIL this migration against live data — do not add one.
