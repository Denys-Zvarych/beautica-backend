-- V120: the historical backfill for bookings.price_max_at_booking, split out of V119.
--
-- WHY ITS OWN VERSION (not appended to V119): Flyway runs an entire migration script inside ONE
-- transaction by default (`mixed=false`; verified across application.yml / application-local.yml /
-- application-prod.yml — the same fact V113:24-32 and V115:7 rely on), and Postgres never
-- downgrades a lock mid-transaction. V119's `ALTER TABLE ... ADD COLUMN` takes ACCESS EXCLUSIVE on
-- `bookings`; had this UPDATE stayed in that file it would have scanned the whole table while that
-- lock was still held, blocking every read and write on the system's hottest table for the
-- duration — with the old instance still serving traffic through a Railway rolling deploy. V119
-- commits first; this scan therefore runs under ROW EXCLUSIVE, which does not conflict with
-- concurrent SELECT/INSERT/UPDATE. V113 encodes the same discipline by ordering its backfill
-- before any DDL; V115 encodes it by giving VALIDATE CONSTRAINT its own transaction boundary.
--
-- NOT batched, deliberately. The scan is driven off the small `service_definitions`/
-- `master_services` side and joins into `bookings` through idx_bookings_master_service_id (V18);
-- at ~50k bookings that is one short statement, and chunking it would trade a bounded ROW
-- EXCLUSIVE scan for N transactions plus a resumability marker this one-shot fixup does not need.
SET LOCAL lock_timeout = '30s';
-- Deliberately looser than V119's 5s. This statement takes no table-level lock that anything can
-- queue behind (ROW EXCLUSIVE conflicts only with DDL), so the fail-fast argument that governs an
-- ACCESS EXCLUSIVE request does not apply here. What lock_timeout still buys is a ceiling on
-- waiting for a ROW-level lock held by a concurrent booking mutation on the old instance — and a
-- value as short as 5s would make the migration flake against perfectly ordinary write traffic.

SET LOCAL statement_timeout = '5min';
-- NOT a duplicate of lock_timeout — the two bound DIFFERENT things and neither implies the other.
-- lock_timeout caps how long a statement WAITS to ACQUIRE a lock; it never fires once the lock is
-- held, so a statement that acquires instantly and then runs forever is entirely unbounded by it.
-- statement_timeout caps elapsed time PER STATEMENT (lock wait included). Without this line the
-- "one short, bounded scan" premise asserted above had nothing enforcing it: a stale-statistics
-- plan flip (seq scan on `bookings` instead of the idx_bookings_master_service_id nested loop) or
-- heavy table bloat lets the UPDATE grind for an unbounded time with ZERO lock contention, so
-- lock_timeout stays silent and the only remaining bound is connection death — precisely the
-- startup crash-loop class documented in memory `project_flyway_checksum_recovery.md`.
--
-- 5min, not tighter: this must not flake on a healthy run. At the ~50k bookings this migration was
-- sized for the scan is seconds, so 5min is ~2 orders of magnitude of headroom and only fires on a
-- genuinely pathological plan. On timeout Flyway rolls the transaction back cleanly (the backfill
-- is idempotent — see the freshness-guard note above — so a later retry is safe and lossless) and
-- the deploy fails loudly, which is strictly better than a hung connection.
--
-- PER STATEMENT, LITERALLY — there is no transaction-wide equivalent in Postgres; the timeout is
-- re-armed for each statement. Four statements follow this SET LOCAL (three ANALYZEs and the
-- UPDATE), so the true worst-case ceiling on this migration is ~20min, NOT 5min. That is stated
-- rather than tightened on purpose: the UPDATE is the only statement that genuinely wants 5min of
-- headroom (it is the one scanning the system's largest table), and the three ANALYZEs preceding
-- it are sample-based, bounded by default_statistics_target rather than by table size — each is
-- seconds even on a large `bookings`, so the realistic ceiling here is the UPDATE's 5min. Reaching
-- the 20min figure would require all four statements to be simultaneously pathological, at which
-- point the deploy is failing regardless of the value on this line. V121 makes the opposite trade
-- (1min x 5 statements) because its statements are all small-table work with no such asymmetry.
--
-- Since statement_timeout also covers the lock wait, the effective per-statement contract is: <=30s
-- waiting for a row lock (lock_timeout fires first), <=5min elapsed. Both are SET LOCAL, so both
-- revert when Flyway commits this migration's transaction — no leakage into the pooled connection.

-- Applies the OLD read-time rule as the best available reconstruction of history for rows created
-- before the snapshot column existed. price_type is persisted as a VARCHAR(10) via
-- @Enumerated(EnumType.STRING) (see V67), so it is matched by string literal, NOT by ordinal.
--
--   price_override IS NOT NULL -> the master fixed their own price; price_at_booking already
--                                 equals that override. Single price, no ceiling -> stays NULL.
--   price_type = 'FIXED'       -> single price, no ceiling -> stays NULL.
--   price_type = 'RANGE' and no override -> price_at_booking is the floor (base_price) and the
--                                 ceiling is service_definitions.price_max.
--
-- FRESHNESS GUARD `sd.updated_at <= b.created_at AND ms.updated_at <= b.created_at`: the three
-- predicates above read CURRENT service state, so without this guard they answer a question about
-- today and stamp the answer onto a booking agreed months ago — fabricating band history that the
-- client never saw, indistinguishable after the write from a genuinely frozen value, in a one-shot
-- migration that is irreversible in practice. Three concrete cases slip past the
-- `sd.price_max >= b.price_at_booking` sanity guard below:
--   * an override DROPPED since the booking — `ms.price_override IS NULL` is true today, but the
--     frozen price_at_booking is that stale override, not base_price, so the row is not the floor
--     of the band being attached to it;
--   * a FIXED -> RANGE flip since the booking — invents a band on a booking agreed at one price
--     (exactly the drift this whole change exists to eliminate, and the case pinned by
--     BookingPriceRangeContractIT#should_keepNullCeiling_when_providerFlipsServiceToRangeAfterBooking
--     on the read path);
--   * base_price RAISED since the booking — floor and ceiling now straddle a wider band than was
--     agreed.
-- Both columns are NOT NULL DEFAULT now() (V6:17, V7:14), so the comparison is always defined. A
-- row whose service was touched after the booking simply stays NULL and renders as the single
-- frozen price_at_booking: faithful by construction, rather than plausibly wrong.
--
-- The guard is also what makes `price_max_at_booking IS NULL` a sound idempotency predicate. On
-- its own that predicate is idempotent only w.r.t. ALREADY-FROZEN values; it cannot distinguish
-- them from LEGITIMATELY-NULL ones, so a manual replay (a repair run, a restore-and-rerun) would
-- happily backfill a booking that was FIXED at booking time but whose service has since become
-- RANGE. With the freshness guard, any service edited after the booking is excluded on every
-- replay, so re-running this statement is a no-op against the rows it must not touch.
--
-- SANITY GUARD `sd.price_max >= b.price_at_booking`: for a NEW row the invariant floor <= ceiling
-- is structural (the ceiling is only ever frozen when price_override IS NULL, so the floor is
-- base_price, and chk_service_def_price_mode from V67 already guarantees price_max >= base_price).
-- Kept as belt-and-braces behind the freshness guard: reconstructing an inverted band would
-- materialise exactly the "400-350 UAH" defect this change eliminates. Such rows are left NULL,
-- which renders as a single price: unknown-but-sane, rather than visibly wrong.

-- REFRESH STATISTICS FIRST. The "one short scan driven off the small service_definitions/
-- master_services side, nested-looping into bookings through idx_bookings_master_service_id"
-- premise asserted at the top of this file is the PLANNER'S CHOICE, not a guarantee — and two
-- things actively push it the wrong way here:
--   * `b.price_max_at_booking IS NULL` matches 100% of rows immediately after V119 added the
--     column, so it contributes no selectivity — yet with no statistics at all for that column the
--     planner falls back to a hardcoded null-fraction guess, which can make the bookings side look
--     far smaller than it is and flip the join to drive off `bookings` with a seq scan;
--   * V119 committed only moments earlier and autovacuum has not necessarily run since.
-- A seq scan on bookings is exactly the pathological plan statement_timeout above exists to abort,
-- i.e. a failed deploy. ANALYZE is the cheap way to make the good plan the chosen one rather than
-- the hoped-for one.
--
-- ANALYZE (unlike VACUUM) IS permitted inside a transaction block, so this runs fine under Flyway's
-- default one-transaction-per-migration. It takes SHARE UPDATE EXCLUSIVE, which does NOT conflict
-- with the ROW EXCLUSIVE held by concurrent booking INSERT/UPDATE/DELETE on the old instance — only
-- with DDL, another ANALYZE/VACUUM, and autovacuum. Postgres never releases a lock mid-transaction,
-- so that mode is held until Flyway commits this migration; on a rolling deploy that blocks nothing
-- application traffic does. The statistics write itself is transactional and rolls back with the
-- migration on failure, which is harmless — it only returns the planner to the state it was already
-- in.
ANALYZE bookings;
ANALYZE master_services;
ANALYZE service_definitions;

UPDATE bookings b
SET price_max_at_booking = sd.price_max
FROM master_services ms
         JOIN service_definitions sd ON sd.id = ms.service_def_id
WHERE b.master_service_id = ms.id
  AND b.price_max_at_booking IS NULL
  AND ms.price_override IS NULL
  AND sd.price_type = 'RANGE'
  AND sd.price_max IS NOT NULL
  AND sd.price_max >= b.price_at_booking
  AND sd.updated_at <= b.created_at
  AND ms.updated_at <= b.created_at;
