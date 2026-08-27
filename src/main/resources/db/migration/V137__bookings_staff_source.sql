-- Phase 22.1 — Staff booking source & schema.
--
-- Adds the third booking provenance, 'STAFF': a walk-in / phone booking entered by a
-- SALON_OWNER or SALON_ADMIN on behalf of one of their salon's masters. Like APP and LINK
-- it is auto-confirmed at creation (track 24.x / V113 retired PENDING); this migration adds
-- no status-writing path of any kind. The AWAITING_CLOSURE notion stays read-time derived
-- (status = 'CONFIRMED' AND ends_at < now) and is deliberately NOT materialised here.
--
-- Schema + enum + entity only. Validation/orchestration lands in 22.2, the endpoint in 22.4.

-- ── Lock + statement guards (must precede every DDL statement below) ─────────────────────
-- Every statement in this migration takes ACCESS EXCLUSIVE on `bookings`, the system's hottest
-- table. Postgres lock requests are FIFO, so a PENDING ACCESS EXCLUSIVE request blocks every
-- subsequent read AND write on `bookings` behind it: an unbounded wait here does not merely stall
-- the deploy, it takes the booking table down while the old instance is still serving traffic
-- through a Railway rolling deploy. Hikari is pool-size 10 / 20s connection timeout, so that stall
-- surfaces as app-wide 500s within seconds. Same 5s value and same argument as V118:30, V119:37,
-- V121:38 and V128:52 — fail fast, let Flyway roll this transaction back cleanly, retry next deploy.
--
-- Unlike those four, this migration ALSO needs statement_timeout. Their DDL is catalog-only once
-- the lock is granted (DROP INDEX = catalog edit + file unlink; ADD COLUMN with no default = a
-- catalog edit), so the only thing that can stall is the lock WAIT and bounding the wait is
-- sufficient. V137 instead runs size-dependent work WHILE HOLDING the lock: the merged ALTER below
-- validates two rewritten CHECK constraints plus the new FK (RI_Initial_Check) in a table scan, and
-- CREATE INDEX then builds an index. Both scale with the row count, so the wait bound alone leaves
-- the held-lock phase unbounded. 1min bounds each of them.
--
-- READ THE UNIT LITERALLY, exactly as V120:42 and V121:47 spell out: Postgres applies
-- statement_timeout PER STATEMENT, re-armed for each one — there is no transaction-wide equivalent,
-- so this line does NOT buy a 1min ceiling on the migration. Three statements follow it (the
-- DROP-CONSTRAINT ALTER, the merged ALTER, the CREATE INDEX), giving a literal ceiling of ~3min.
-- The DROP-CONSTRAINT ALTER is catalog-only and completes the instant it holds the lock, so the
-- realistic worst case is the two size-dependent statements: ~2min of ACCESS EXCLUSIVE held, not
-- 1min. And the lock is held for that whole span regardless — Postgres releases it only at COMMIT,
-- never between statements. The 5s lock wait is counted INSIDE each statement's own 1min rather
-- than added on top, since statement_timeout covers the lock wait too (V120:29); lock_timeout is
-- what makes that wait component fail at 5s instead of eating the minute.
--
-- SET LOCAL: scoped to Flyway's per-migration transaction, so neither value leaks to the pooled
-- connection once this migration commits.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '1min';

-- 1) Drop both polymorphic constraints in ONE statement. Postgres has no ALTER CONSTRAINT for
--    CHECK, so each named constraint is dropped and re-added. V89 declared chk_bookings_source
--    inline on the column; the DROP/ADD pair re-declares it as a table constraint under the same
--    name. A DROP CONSTRAINT is a catalog edit only (no table pass), but merging the two keeps the
--    ACCESS EXCLUSIVE acquisitions to one.
ALTER TABLE bookings
    DROP CONSTRAINT chk_bookings_source,
    DROP CONSTRAINT chk_bookings_guest_fields;

-- 2) One merged ALTER TABLE: new column + both rewritten constraints.
--
--    !! DO NOT SPLIT THIS BACK INTO SEPARATE `ALTER TABLE` STATEMENTS. !!
--    Each ALTER TABLE triggers its own full-table pass — the two validating ADD CONSTRAINTs scan
--    for violating rows, and the inline FK validates via RI_Initial_Check. Postgres collapses
--    subcommands merged into a SINGLE ALTER TABLE into ONE phase-3 scan, so both rewritten CHECKs
--    are evaluated in one heap scan instead of two (ADD COLUMN nullable-with-no-default is
--    catalog-only on PG 11+ and forces no rewrite of its own).
--
--    Do not over-claim that saving: the FK is NOT folded into that scan. ATRewriteTables calls
--    validateForeignKeyConstraint AFTER the phase-3 scan has finished, and RI_Initial_Check then
--    issues its own `SELECT ... FROM ONLY bookings fk LEFT JOIN ONLY users pk ... WHERE pk.id IS
--    NULL AND fk.created_by_user_id IS NOT NULL` — an independent full scan. So the honest count is
--    3 table passes (merged phase-3 scan + FK validation + the CREATE INDEX below) instead of 4:
--    exactly one CHECK scan eliminated, no more.
--
--    The larger win is the LOCK ACQUISITIONS, and that one is fully real: ACCESS EXCLUSIVE is taken
--    ONCE instead of three times. That matters more than the scan count under the rolling-deploy
--    scenario the lock_timeout above guards.
--
--    ── chk_bookings_guest_fields: the polymorphic identity invariant, widened to admit STAFF ──
--
--    !! DO NOT "SIMPLIFY" THE STAFF BRANCH TO A SINGLE MODE. !!
--    The STAFF branch below is deliberately TWO-MODE — linked-client XOR walk-in — even
--    though the 2026-08-18 scope decision ships only the walk-in mode in the service layer
--    (the existing-platform-CLIENT identity mode is DEFERRED to a re-scoped Phase 22.3).
--    The schema is the expensive thing to change later; the service layer is not. Keeping
--    the client_id branch live here means a future phase can light up linked-client staff
--    bookings with ZERO migration. Narrowing this CHECK to match today's service scope
--    would trade a free future change for a paid one — leave both modes in place.
--
--    STAFF identity modes:
--      a) Linked client — client_id NOT NULL, all guest_* NULL (same shape as APP).
--      b) Walk-in       — client_id NULL, guest_name AND guest_surname AND guest_phone all
--                         NOT NULL and, for the two name columns, non-blank. Fully anonymous
--                         (no name) is NOT permitted: first name, last name and phone are all
--                         required for an account-less staff booking. Note this is STRICTER
--                         than the LINK branch, where guest_surname stays optional (see V91's
--                         rationale) — the two branches intentionally differ and must not be
--                         unified.
--    Either mode requires cancel_token IS NULL: a staff booking has no self-service guest
--    cancel link; only the owner/admin cancels it through a management endpoint.
--
--    !! THE BLANK GUARD IS TWO CLAUSES PER COLUMN, NOT ONE — DO NOT "SIMPLIFY" IT EITHER. !!
--    `guest_name IS NOT NULL` alone is satisfied by '' and by '   ', which is exactly the
--    no-usable-identity row the walk-in branch exists to reject, and it is unrecoverable after the
--    fact: a walk-in has no account to recover a name from. But replacing the IS NOT NULL clause
--    with `btrim(guest_name) <> ''` on its own SILENTLY RE-OPENS THE NULL HOLE — btrim(NULL) is
--    NULL, `NULL <> ''` is NULL, and a CHECK constraint treats an unknown result as SATISFIED.
--    (Empirically confirmed: that one-liner turned the existing missing-name / missing-surname
--    tests red.) Both clauses are required, in this order. guest_phone deliberately gets no such
--    clause: chk_bookings_guest_phone_format (V89) already pins it to '^\+[0-9]{6,18}$' whenever
--    it is non-null, which no blank or whitespace string can match — a btrim clause there would be
--    dead weight asserting something already impossible.
--
--    The APP and LINK branches are carried over BYTE-FOR-BYTE from V91 — in particular the
--    LINK branch keeps V91's terminal-status relaxation of the cancel_token clause. Copying
--    V89's original (unrelaxed) LINK predicate here would re-open the exact bug V91 fixed:
--    the guest-cancel UPDATE nulls cancel_token while setting status = 'CANCELLED', and an
--    unrelaxed clause makes every real guest cancellation 500.
--
--    Every pre-existing APP and LINK row satisfies the rewritten predicate unchanged (both
--    branches are verbatim copies), so the validating ADD CONSTRAINT cannot fail on existing data
--    and no NOT VALID / VALIDATE split is warranted.
--
--    chk_bookings_guest_phone_format (V89) is untouched and still applies: a STAFF walk-in
--    guest_phone must match '^\+[0-9]{6,18}$'. The 22.2 service layer must normalise the
--    staff-entered phone to E.164 before persisting or the insert will be rejected.
--
--    ── created_by_user_id: which staff user created this booking ──
--    Orthogonal to booking_source — the enum answers "what kind of booking", this column
--    answers "which human keyed it in". Nullable so every historical APP/LINK row stays
--    valid; STAFF rows populate it as a soft, application-layer expectation, deliberately
--    NOT a DB CHECK (coupling the polymorphic CHECK to yet another column buys nothing and
--    makes every future widening more expensive).
--
--    ON DELETE RESTRICT, not SET NULL: this column exists for ATTRIBUTION, and SET NULL makes that
--    attribution destructible by the very act that most often follows abuse — deleting the account
--    under suspicion. Worse, the resulting NULL is indistinguishable from a pre-V137 row that never
--    had a creator at all, so forensics could not separate "legacy" from "attribution erased": a
--    repudiation path. RESTRICT matches the codebase's actual lifecycle, which is
--    deactivate-never-delete (no userRepository.delete*, no @DeleteMapping under user/ or auth/ —
--    accounts are flagged is_active = false), so nothing in the app can hit this constraint today.
--    GDPR NOTE: a future right-to-erasure flow MUST handle this column explicitly — ANONYMISE the
--    creating user's row (or re-point/scrub this column deliberately) rather than hard-DELETE it.
--    RESTRICT will block a hard delete of any user who ever created a booking, and that rejection
--    is the intended behaviour, not a bug to be worked around by reverting to SET NULL.
ALTER TABLE bookings
    ADD COLUMN created_by_user_id UUID REFERENCES users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT chk_bookings_source CHECK (
        booking_source IN ('APP', 'LINK', 'STAFF')
    ),
    ADD CONSTRAINT chk_bookings_guest_fields CHECK (
        (booking_source = 'LINK'
            AND client_id IS NULL
            AND guest_name IS NOT NULL
            AND guest_phone IS NOT NULL
            AND (cancel_token IS NOT NULL
                 OR status IN ('CANCELLED', 'COMPLETED', 'NOT_COMPLETED', 'DECLINED')))
        OR
        (booking_source = 'APP'
            AND client_id IS NOT NULL
            AND guest_name IS NULL
            AND guest_surname IS NULL
            AND guest_phone IS NULL
            AND cancel_token IS NULL)
        OR
        (booking_source = 'STAFF'
            AND cancel_token IS NULL
            AND (
                (client_id IS NOT NULL
                    AND guest_name IS NULL
                    AND guest_surname IS NULL
                    AND guest_phone IS NULL)
                OR
                (client_id IS NULL
                    AND guest_name    IS NOT NULL AND btrim(guest_name)    <> ''
                    AND guest_surname IS NOT NULL AND btrim(guest_surname) <> ''
                    AND guest_phone IS NOT NULL)
            ))
    );

-- 3) Partial index on created_by_user_id.
--    Its GUARANTEED consumer is the FK's referential-integrity lookup: every DELETE of a users row
--    must prove no booking references it, i.e. `WHERE created_by_user_id = $1`. The partial
--    predicate serves that lookup because Postgres proves `x = $1 => x IS NOT NULL`, so the planner
--    can use this index for the RI probe. Without it that probe is a seq scan of `bookings` per
--    deleted user — and under ON DELETE RESTRICT the probe is what enforces the constraint, so it
--    is not optional work.
--
--    It ALSO serves the straightforward "bookings created by staff member X" equality lookup if a
--    later phase adds one.
--
--    It deliberately does NOT serve the 22.4 self-booking predicate
--    `created_by_user_id = (SELECT user_id FROM masters WHERE id = booking.master_id)` — that
--    comparison is ROW-CORRELATED (the right-hand side varies per candidate row), so a btree on
--    created_by_user_id alone offers no seek and the planner will never choose it for that shape.
--    Do not widen this index on the speculation that it might; if 22.4's predicate turns out to
--    need index support, that needs its own measurement and its own migration.
--
--    Partial: the vast majority of rows are APP/LINK with a NULL creator, and indexing them would
--    be pure write amplification (§E-5 / §O-6).
CREATE INDEX idx_bookings_created_by ON bookings (created_by_user_id)
    WHERE created_by_user_id IS NOT NULL;
