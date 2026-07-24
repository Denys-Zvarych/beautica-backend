-- Track 24.x — booking auto-confirm. Removes the provider approval step: every booking is now
-- created CONFIRMED (both the authenticated APP/staff path and the public LINK guest path were
-- already CONFIRMED at creation for guests; only the authenticated path created PENDING). The
-- PENDING status is retired entirely.
--
-- STATEMENT ORDER MATTERS. The backfill (step 1) runs FIRST, while the OLD constraints still
-- stand: chk_booking_status still allows PENDING, no_overlapping_bookings still covers
-- PENDING+CONFIRMED, and uq_client_idempotency_key_active still covers PENDING+CONFIRMED. Both
-- statuses being inside every old predicate means the covered row-set is unchanged by the flip —
-- the overlap/idempotency invariants hold continuously across it, with no window where a PENDING
-- row could violate a constraint mid-migration. chk_cancellation_reason_status is untouched and
-- safe: a PENDING row can never carry a cancellation_reason (only DECLINED/CANCELLED/
-- NOT_COMPLETED may), and CONFIRMED does not require one either.
UPDATE bookings SET status = 'CONFIRMED' WHERE status = 'PENDING';

-- 1) chk_booking_status (V18) — drop PENDING from the 6-value CHECK.
-- ADD CONSTRAINT ... NOT VALID is metadata-only (instant) and, from the moment THIS statement
-- commits, enforces the CHECK on every NEW insert/update — the backfill on line 14 above already
-- guarantees every EXISTING row satisfies it, so there is no enforcement gap of any kind, only a
-- deferred retroactive scan.
--
-- That retroactive scan (VALIDATE CONSTRAINT) is deliberately NOT run in this same file — see
-- V115. MEDIUM finding fixed here: a same-migration "NOT VALID then VALIDATE CONSTRAINT" is
-- COSMETIC, not a real lock-avoidance idiom. Flyway runs an entire migration script inside ONE
-- transaction by default (`mixed=false`; no `spring.flyway.mixed` / executeInTransaction=false
-- anywhere in this repo), and Postgres never downgrades a lock mid-transaction — so a
-- VALIDATE CONSTRAINT appended right here would still run its full-table scan while this very
-- transaction is still holding the ACCESS EXCLUSIVE lock the ADD CONSTRAINT above just took.
-- Net lock severity/duration would be identical to a single validating ADD CONSTRAINT. The
-- lighter SHARE UPDATE EXCLUSIVE lock VALIDATE CONSTRAINT can take only materializes across a
-- transaction boundary, i.e. a separate Flyway version, which commits and releases the ACCESS
-- EXCLUSIVE lock before V115's VALIDATE CONSTRAINT statements begin their scan.
ALTER TABLE bookings DROP CONSTRAINT chk_booking_status;
ALTER TABLE bookings
    ADD CONSTRAINT chk_booking_status CHECK (
        status IN ('CONFIRMED','DECLINED','COMPLETED','NOT_COMPLETED','CANCELLED')
    ) NOT VALID;
-- VALIDATE CONSTRAINT chk_booking_status runs in V115 (separate transaction — see comment above).

-- 2) no_overlapping_bookings (V18) — the EXCLUDE gist index backing the DB-level double-booking
-- guard. Narrowing WHERE (status IN ('PENDING','CONFIRMED')) to WHERE (status = 'CONFIRMED')
-- takes ACCESS EXCLUSIVE and fully rebuilds the gist index inside this transaction —
-- CREATE INDEX CONCURRENTLY cannot run in a transaction and ALTER TABLE ... ADD CONSTRAINT ...
-- EXCLUDE has no USING INDEX form, so a blocking rebuild is unavoidable here. Milliseconds at
-- current (pre-launch) table size; a maintenance window would be required for this class of
-- change at scale.
ALTER TABLE bookings DROP CONSTRAINT no_overlapping_bookings;
ALTER TABLE bookings
    ADD CONSTRAINT no_overlapping_bookings EXCLUDE USING gist (
        master_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (status = 'CONFIRMED');

-- 3) uq_client_idempotency_key_active (V18) — partial unique index; also filtered on PENDING.
DROP INDEX uq_client_idempotency_key_active;
CREATE UNIQUE INDEX uq_client_idempotency_key_active
    ON bookings (client_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL AND status = 'CONFIRMED';

-- 4) idx_bookings_master_slot_overlap (V26) — master-scoped overlap-check index.
DROP INDEX idx_bookings_master_slot_overlap;
CREATE INDEX idx_bookings_master_slot_overlap
    ON bookings (master_id, starts_at, ends_at)
    WHERE status = 'CONFIRMED';

-- 5) idx_bookings_client_slot_overlap (V112) — client-scoped cross-master overlap-check index.
DROP INDEX idx_bookings_client_slot_overlap;
CREATE INDEX idx_bookings_client_slot_overlap
    ON bookings (client_id, starts_at, ends_at)
    WHERE status = 'CONFIRMED' AND client_id IS NOT NULL;

-- 6) idx_bookings_salon_status_starts_at (V22) — salon calendar / booking-list index.
-- Verified NOT dead (track 24.7 perf audit, Finding 4): DashboardService.REVENUE_SQL filters
-- `b.salon_id = ANY(:salonIds) AND b.status = 'COMPLETED' AND b.starts_at BETWEEN :fromDate AND
-- :toDate` directly against bookings.salon_id — an exact match for this composite/partial index.
-- The rebuild (not a straight DROP) is kept.
DROP INDEX idx_bookings_salon_status_starts_at;
CREATE INDEX idx_bookings_salon_status_starts_at
    ON bookings (salon_id, status, starts_at DESC)
    WHERE status IN ('CONFIRMED', 'COMPLETED');

-- chk_bookings_guest_fields (V89/V91) — verified: it never names PENDING (its cancel_token
-- clause names CANCELLED/COMPLETED/NOT_COMPLETED/DECLINED only). No change needed here; this
-- comment records that the check was made so a future reader doesn't re-open it (decision D4).
