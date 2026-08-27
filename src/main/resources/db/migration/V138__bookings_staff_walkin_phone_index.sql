-- V138 — index backing the per-recipient walk-in SMS-spend cap (Phase 22.7 hardening).
--
-- WHY
-- Phase 22.7 made POST /api/v1/masters/{masterId}/bookings an SMS-SPEND path: every successful
-- walk-in create dispatches a Beautica-branded confirmation to a staff-typed Ukrainian number that
-- never opted in. StaffBookingService#assertWalkInSmsBudgetForPhone therefore counts recent
-- STAFF-sourced bookings for the normalised recipient before it takes the per-master advisory lock:
--
--   SELECT count(*) FROM bookings
--    WHERE guest_phone = ? AND booking_source = 'STAFF' AND created_at > ?
--
-- Nothing indexed guest_phone (V89 added the column and its format CHECK only), so that count was a
-- sequential scan of the whole bookings table on EVERY walk-in create — a throttle that degrades the
-- endpoint it protects is not a throttle worth having.
--
-- SHAPE — composite AND partial, mirroring the predicate exactly (Anti-Bug §E-5 / §O-6)
--   * (guest_phone, created_at) rather than (guest_phone) alone: the query always carries the time
--     window, so the second column turns the range filter into an index-range scan instead of a heap
--     probe per matching row. A phone that HAS been booked many times is precisely the case the cap
--     exists for, so the many-rows-per-phone shape is the one to optimise.
--   * WHERE booking_source = 'STAFF' rather than a third indexed column: the predicate is a constant
--     in the only query that uses this index, so folding it into the index definition keeps the
--     index small (STAFF is a minority of bookings) and removes a recheck. Guest (LINK) rows, whose
--     phones are OTP-verified and throttled by PhoneOtpService, are excluded entirely; APP rows have
--     no guest_phone at all.
--   * No redundant single-column index is being dropped here, because none was ever created.
--
-- Not expressed on the entity as @Table(indexes = ...): JPA's @Index cannot declare a partial index,
-- so a mirrored annotation would describe a DIFFERENT index than the one that exists and would drift
-- silently. The predicate is documented on BookingRepository#countStaffWalkInsForPhoneSince instead.
--
-- Immutable-migration rule (Anti-Bug §O-9): this is a new, fix-forward version (max existing = V137).

-- ── Lock + statement guards (must precede the DDL statement below) ───────────────────────
-- Same argument, same table and the same 5s value as V118:30, V119:37, V121:38, V128:52 and V137:41.
-- `CREATE INDEX` (not CONCURRENTLY — Flyway wraps each migration in one transaction, and CREATE
-- INDEX CONCURRENTLY cannot run inside one) takes SHARE on `bookings`, the system's hottest table:
-- SHARE conflicts with ROW EXCLUSIVE, so it blocks every INSERT/UPDATE/DELETE for the whole build.
-- Postgres lock requests are FIFO, so the PENDING SHARE request also queues ahead of every
-- subsequent reader — reads degrade too, all while the old instance is still serving traffic through
-- a Railway rolling deploy. Hikari is pool-size 10 / 20s connection timeout, so that stall surfaces
-- as app-wide 500s within seconds. Fail fast, let Flyway roll this transaction back cleanly, retry
-- next deploy.
--
-- statement_timeout as well as lock_timeout, for V137:23's reason and not V118's: bounding the lock
-- WAIT is sufficient only when the DDL is catalog-only once granted (DROP INDEX, ADD COLUMN with no
-- default). Building an index is size-dependent work performed WHILE HOLDING the lock, so the wait
-- bound alone leaves the held-lock phase unbounded. 1min bounds it.
--
-- Read the unit literally (V120:42, V121:47, V137:28): statement_timeout is applied PER STATEMENT,
-- re-armed for each one — there is no transaction-wide equivalent. Exactly one statement follows, so
-- here the literal ceiling really is 1min. The 5s lock wait is counted INSIDE that 1min rather than
-- added on top, since statement_timeout covers the lock wait too (V120:29); lock_timeout is what
-- makes that wait component fail at 5s instead of eating the minute.
--
-- SET LOCAL: scoped to Flyway's per-migration transaction, so neither value leaks to the pooled
-- connection once this migration commits.
--
-- CREATE INDEX CONCURRENTLY is the real long-term answer and is deliberately NOT used here — it
-- cannot run inside Flyway's transaction. Logged as a pre-release checklist item, not this phase.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '1min';

CREATE INDEX IF NOT EXISTS idx_bookings_staff_walkin_phone
    ON bookings (guest_phone, created_at)
    WHERE booking_source = 'STAFF';
