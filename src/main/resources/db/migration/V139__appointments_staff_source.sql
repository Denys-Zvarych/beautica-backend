-- V139 — STAFF-sourced appointment headers (Phase 22.8).
--
-- Lets `appointments` hold the header of a walk-in visit created by a SALON_OWNER / SALON_ADMIN /
-- INDEPENDENT_MASTER, so a multi-service walk-in produces the same one-header-plus-N-chained-rows
-- shape a client visit produces (V124/V125). Additive: every existing APP and LINK row keeps its
-- meaning, and a pre-V139 single-service STAFF booking keeps `appointment_id = NULL` forever.
--
-- TWO constraints must widen, not one. chk_appointment_source (V124:52) enumerates APP/LINK, and
-- chk_appointment_guest_fields (V126:23) is a two-branch disjunction whose STAFF case matches
-- NEITHER arm — widening only the first yields a migration that passes Flyway and then rejects
-- every insert with a 500.
--
-- NOT VALID / VALIDATE SPLIT (V113 house style; V140 carries the VALIDATE). V137 validated inline
-- on `bookings` and argued it was safe because the carried-over branches cannot reject existing
-- rows. True, and still the wrong trade: that validation scan runs WHILE HOLDING ACCESS EXCLUSIVE,
-- which is the exact risk V137's own header spends its length bounding. NOT VALID makes this
-- migration's ACCESS EXCLUSIVE phase catalog-only, and moves the scans into V140 under SHARE UPDATE
-- EXCLUSIVE, which blocks neither readers nor writers. Do not "simplify" this back into one file:
-- Postgres will not downgrade the lock mid-transaction, so a same-migration VALIDATE is cosmetic.
--
-- Immutable-migration rule (Anti-Bug §O-9): fix-forward, max existing = V138.

-- ── Lock + statement guards (must precede every DDL statement below) ─────────────────────
-- Same argument and the same 5s value as V118:30, V119:37, V121:38, V128:52, V137:41 and V138.
-- `appointments` is smaller and colder than `bookings`, but it is FK-referenced by
-- bookings.appointment_id and read on every visit-detail request, so an unbounded ACCESS EXCLUSIVE
-- wait during a Railway rolling deploy still stalls live traffic behind a FIFO lock queue. Fail
-- fast, let Flyway roll this transaction back cleanly, retry next deploy.
--
-- statement_timeout as well: CREATE INDEX below is size-dependent work performed WHILE HOLDING the
-- lock, so bounding the wait alone leaves the held-lock phase unbounded. Read the unit literally
-- (V120:42, V121:47, V137:28) — statement_timeout is applied PER STATEMENT, re-armed for each one;
-- three statements follow, so the literal ceiling is ~3min, of which only the CREATE INDEX is
-- size-dependent (the two ALTERs are catalog-only by construction now that both CHECKs and the FK
-- are NOT VALID).
--
-- SET LOCAL: scoped to Flyway's per-migration transaction, so neither value leaks to the pool.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '1min';

-- 1) Drop both polymorphic constraints in ONE statement. Postgres has no ALTER CONSTRAINT for
--    CHECK, so each named constraint is dropped and re-added under the same name. A DROP CONSTRAINT
--    is a catalog edit only; merging the two keeps the ACCESS EXCLUSIVE acquisitions to one.
ALTER TABLE appointments
    DROP CONSTRAINT chk_appointment_source,
    DROP CONSTRAINT chk_appointment_guest_fields;

-- 2) One merged ALTER TABLE: the attribution column, its NOT VALID FK, and both rewritten NOT VALID
--    CHECKs. Merged for the LOCK ACQUISITIONS (one ACCESS EXCLUSIVE instead of four), not for scan
--    count — with every added constraint marked NOT VALID there is no phase-3 scan to save, and
--    ADD COLUMN nullable-with-no-default is catalog-only on PG 11+.
--
--    ── created_by_user_id: which staff user keyed this visit in ──
--    Orthogonal to booking_source, exactly as on `bookings` (V137): the enum answers "what kind of
--    visit", this column answers "which human created it". Nullable so every historical APP/LINK
--    header stays valid; STAFF headers populate it as an application-layer expectation, deliberately
--    NOT a DB CHECK (coupling the polymorphic CHECK to another column buys nothing and makes every
--    future widening more expensive).
--
--    ON DELETE RESTRICT, not SET NULL — V137's argument verbatim: this column exists for
--    ATTRIBUTION, and SET NULL makes that attribution destructible by the very act that most often
--    follows abuse (deleting the account under suspicion), leaving a NULL indistinguishable from a
--    pre-V139 row that never had a creator. RESTRICT matches the codebase's deactivate-never-delete
--    lifecycle, so nothing in the app can hit it today.
--    GDPR NOTE: a future right-to-erasure flow MUST handle this column explicitly — ANONYMISE the
--    creating user's row rather than hard-DELETE it. RESTRICT blocking that delete is the intended
--    behaviour, not a bug to work around by reverting to SET NULL.
--
--    ── chk_appointment_guest_fields, widened with a STAFF branch ──
--    The LINK and APP branches are carried over BYTE-FOR-BYTE from V126:24-36 — in particular the
--    LINK branch keeps V126's terminal-status relaxation of the cancel_token clause, without which
--    every real guest visit cancellation 500s (the bug V91/V126 fixed on the child rows).
--
--    !! DO NOT "SIMPLIFY" THE STAFF BRANCH TO A SINGLE MODE. !!
--    It is deliberately TWO-MODE — linked-client XOR walk-in — even though the service layer in
--    22.12 ships only the walk-in mode (the existing-platform-CLIENT mode stays deferred with
--    Phase 22.3). This mirrors V137:60-77 on `bookings` exactly, so header and children cannot
--    diverge on what a STAFF identity is, and so a future phase can light up linked-client staff
--    visits with ZERO migration.
--
--    !! THE BLANK GUARD IS TWO CLAUSES PER COLUMN, NOT ONE. !!
--    `guest_name IS NOT NULL` alone is satisfied by '' and by '   ' — the no-usable-identity row the
--    walk-in branch exists to reject, unrecoverable after the fact because a walk-in has no account
--    to recover a name from. But replacing it with `btrim(guest_name) <> ''` alone SILENTLY
--    RE-OPENS THE NULL HOLE: btrim(NULL) is NULL, `NULL <> ''` is NULL, and a CHECK treats an
--    unknown result as SATISFIED. Both clauses, in this order. guest_phone deliberately gets no
--    btrim clause: chk_appointment_guest_phone_format (V124) already pins it to '^\+[0-9]{6,18}$'
--    whenever non-null, which no blank string can match.
--
--    Either STAFF mode requires cancel_token IS NULL: a staff-created visit has no self-service
--    guest cancel link — only the provider closes it, through a management endpoint.
ALTER TABLE appointments
    ADD COLUMN created_by_user_id UUID,
    ADD CONSTRAINT fk_appointments_created_by
        FOREIGN KEY (created_by_user_id) REFERENCES users(id) ON DELETE RESTRICT
        NOT VALID,
    ADD CONSTRAINT chk_appointment_source CHECK (
        booking_source IN ('APP', 'LINK', 'STAFF')
    ) NOT VALID,
    ADD CONSTRAINT chk_appointment_guest_fields CHECK (
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
    ) NOT VALID;

-- 3) Partial index on created_by_user_id — V137's idx_bookings_created_by, lifted to the header.
--    Its GUARANTEED consumer is the FK's referential-integrity probe: every DELETE of a users row
--    must prove no appointment references it, i.e. `WHERE created_by_user_id = $1`. The partial
--    predicate serves that lookup because Postgres proves `x = $1 => x IS NOT NULL`. Without it that
--    probe is a seq scan of `appointments` per deleted user — and under ON DELETE RESTRICT the probe
--    IS the enforcement, so it is not optional work.
--    Partial because the overwhelming majority of rows are APP/LINK with a NULL creator (§E-5/§O-6).
--    NOT CONCURRENTLY: Flyway wraps each migration in one transaction and CREATE INDEX CONCURRENTLY
--    cannot run inside one. Logged as a pre-release checklist item, not this phase.
CREATE INDEX idx_appointments_created_by ON appointments (created_by_user_id)
    WHERE created_by_user_id IS NOT NULL;
