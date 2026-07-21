-- V119: freeze the booking price CEILING alongside the already-frozen floor.
--
-- Before this migration `priceMaxAtBooking` had no column: it was derived at READ time by
-- BookingPriceRange.resolveCeiling(...) walking booking -> master_services -> service_definitions
-- and reading the LIVE price_type/price_max. That made the ceiling drift under the client's feet:
-- a provider editing their service retroactively rewrote the band on existing (even COMPLETED)
-- bookings, a FIXED->RANGE flip grew a ceiling onto a booking that never had one, and lowering
-- price_max below the frozen price_at_booking rendered an INVERTED band ("400-350 UAH").
--
-- Locked product decision: a past booking must show the band that was agreed AT BOOKING TIME.
-- price_max_at_booking therefore becomes a snapshot column written on the create paths, exactly
-- like price_at_booking (V18) beside which it sits — same NUMERIC(10,2) type/precision.
--
-- NULLABLE by design: NULL means "single price, render price_at_booking alone". That is the
-- majority case (every FIXED service, and every RANGE service whose master set a price_override),
-- so a NOT NULL default would be semantically wrong, not merely inconvenient.
--
-- THIS MIGRATION IS METADATA-ONLY AND MUST STAY THAT WAY. The historical backfill lives in
-- V120, deliberately NOT here. Flyway runs an entire migration script inside ONE transaction by
-- default (`mixed=false`; no `spring.flyway.mixed` / executeInTransaction=false anywhere in this
-- repo — same fact V113:24-32 and V115:7 already rely on), and Postgres never downgrades a lock
-- mid-transaction. `ALTER TABLE ... ADD COLUMN` takes ACCESS EXCLUSIVE on `bookings`, so a
-- backfill UPDATE appended to this file would run its full-table scan while this transaction is
-- STILL holding that lock — during a Railway rolling deploy the old instance is still serving
-- traffic, and every SELECT/INSERT/UPDATE on `bookings` would queue behind it for the whole scan.
-- Splitting at a Flyway version boundary is what actually releases the lock: this migration
-- commits (ACCESS EXCLUSIVE released) before V120's UPDATE begins under mere ROW EXCLUSIVE.
-- This is the same lock-avoidance idiom V113/V115 use for NOT VALID + VALIDATE CONSTRAINT, and
-- the same statement-ordering discipline V113:14 applies by putting its backfill BEFORE any DDL.

-- Fail fast rather than queue. A pending ACCESS EXCLUSIVE lock request blocks every subsequent
-- query on `bookings` behind it (Postgres lock requests are FIFO), so an unbounded wait here does
-- not merely stall the deploy — it takes the booking table down for reads too, and a migration
-- that outlives the connection timeout crash-loops startup (this repo's documented CRITICAL
-- incident class; see memory `project_flyway_checksum_recovery.md`). 5s, then error out: Flyway
-- rolls this transaction back cleanly and the next deploy retries it.
SET LOCAL lock_timeout = '5s';
-- NO statement_timeout here, unlike V120 — and the asymmetry is deliberate, not an oversight.
-- lock_timeout bounds the WAIT for a lock; statement_timeout bounds total EXECUTION. V120 needs
-- both because its UPDATE acquires its lock instantly and then does unbounded work. This file's
-- only statement is `ADD COLUMN <nullable, no default>`, which since PG 11 is a catalog-only edit
-- with no table rewrite: once the lock is granted it completes in milliseconds regardless of table
-- size, so there is no execution phase left for a statement_timeout to bound. Everything that can
-- actually stall here is the lock WAIT, which lock_timeout already caps at 5s. Do not "restore
-- symmetry" by adding one — it would be dead configuration implying a risk this DDL does not carry.

ALTER TABLE bookings ADD COLUMN IF NOT EXISTS price_max_at_booking NUMERIC(10,2);

COMMENT ON COLUMN bookings.price_max_at_booking IS
    'Frozen RANGE ceiling at booking time. NULL = single price (FIXED service, or RANGE with a master price_override) - render price_at_booking alone. Snapshot: never re-derived from current service_definitions state, not on read and not on reschedule.';

-- No CHECK (price_max_at_booking IS NULL OR price_max_at_booking >= price_at_booking) is added.
-- Even as NOT VALID (which skips existing rows) such a constraint is still enforced on every
-- future UPDATE, so a single legacy row that slipped through would become un-cancellable and
-- un-reschedulable — bricking booking mutations to guard an invariant the create paths already
-- hold structurally. V120's guarded backfill is the cheaper, non-trapping enforcement.
