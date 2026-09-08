-- Fail the deploy fast instead of stalling every write on the busiest tables.
--
-- Flyway wraps this file in ONE transaction, so every lock is held SIMULTANEOUSLY until COMMIT.
-- The DROP CONSTRAINT / ADD CONSTRAINT pairs below take ACCESS EXCLUSIVE on `bookings`,
-- `appointments`, `reviews` AND on `users` (the FK re-creations reference it), which blocks every
-- login and every JWT-backed read for the migration's whole duration. Schedule the deploy
-- accordingly. lock_timeout bounds only lock ACQUISITION; statement_timeout bounds the work done
-- while the lock is held. Both are needed, and both are SET LOCAL so neither leaks onto the pooled
-- connection (V159's header documents what a bare SET broke).
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '1min';

-- =============================================================================
-- Phase 300 — CLIENT account self-deletion: detach columns, widened guest-field
-- CHECKs, and the three client-side FK relaxations.
--
-- docs/backend-phases/phase-300-client-account-self-deletion.md
--
-- ADDITIVE AND PERMISSIVE ONLY. Not one existing row changes: every live booking
-- and appointment keeps client_detached_at NULL, which is what all three original
-- CHECK arms now require, so they pass exactly as before.
--
-- WHY. DELETE /api/v1/users/me hard-deletes the CLIENT's users row. Four FKs refuse
-- that today (bookings.client_id, appointments.client_id, reviews.client_id,
-- client_reviews.subject_client_id — all NO ACTION, declared inline with no
-- ON DELETE clause). This migration relaxes the first three; client_reviews rows are
-- deleted by the service before the user row goes, so that FK needs no change.
--
-- Phase 294 (V157) left these four alone because it was a STAFF-scoped phase with no
-- reason to reach them. That is SCOPE, NOT a decision that client-side FKs must stay
-- NO ACTION. Do not cite V157 as precedent against this file.
--
-- !! ORDER OF OPERATIONS FOR THE DELETE — READ THIS BEFORE WRITING THE SERVICE !!
-- The detach must be ONE UPDATE that writes the sentinel AND nulls client_id AND
-- stamps client_detached_at together, and it must run BEFORE the users row is deleted:
--
--   1) UPDATE bookings SET client_id = NULL, guest_name = 'Видалений клієнт',
--             guest_surname = NULL, client_detached_at = :now
--       WHERE client_id = :userId AND <terminal/past>;
--   2) (same for appointments)
--   3) DELETE FROM users WHERE id = :userId;
--
-- The FK's ON DELETE SET NULL below CANNOT perform step 1 on its own. It writes exactly
-- one column, and Postgres enforces CHECK constraints on the UPDATE that SET NULL
-- performs — the resulting row satisfies no arm of the widened CHECK, so the DELETE
-- ABORTS. Postgres cannot defer a CHECK either (only UNIQUE / PK / FK / EXCLUDE are
-- deferrable), so no in-transaction ordering rescues a bare DELETE. That is deliberate:
-- it makes losing the sentinel impossible, and it makes a missed row fail LOUDLY instead
-- of silently publishing a nameless client on a provider's own history.
-- Pinned by ClientDetachCoherenceIT.
-- =============================================================================

-- ── 1. the detachment discriminator (mirrors masters.detached_at, V157 section 1) ──
ALTER TABLE bookings     ADD COLUMN client_detached_at TIMESTAMPTZ;
ALTER TABLE appointments ADD COLUMN client_detached_at TIMESTAMPTZ;

COMMENT ON COLUMN bookings.client_detached_at IS
    'Phase 300. When this booking was detached from its (now hard-deleted) client users row. '
    'NULL <=> attached. This column, not client_id alone, is the state discriminator in '
    'chk_bookings_guest_fields — booking_source is NEVER rewritten, because it records how the '
    'visit was booked and overwriting it would falsify every source-keyed query.';

COMMENT ON COLUMN appointments.client_detached_at IS
    'Phase 300. Mirror of bookings.client_detached_at for the multi-service visit header. Header '
    'and children must never diverge on this column.';

-- ── 2. widen chk_bookings_guest_fields with a fourth, detached arm ─────────────────
-- The three original arms each gain `client_detached_at IS NULL` so the four states stay
-- disjoint. The fourth arm is deliberately source-agnostic: a STAFF booking that referenced
-- a registered client detaches by exactly the same rule as an APP booking. LINK bookings
-- never have a client and so never reach it.
-- guest_surname stays NULL and guest_phone stays NULL: the sentinel is one string and the
-- phone is PII that must not survive the delete.
ALTER TABLE bookings DROP CONSTRAINT chk_bookings_guest_fields;

ALTER TABLE bookings
    ADD CONSTRAINT chk_bookings_guest_fields CHECK (
        (booking_source = 'LINK'
            AND client_detached_at IS NULL
            AND client_id IS NULL
            AND guest_name IS NOT NULL
            AND guest_phone IS NOT NULL
            AND (cancel_token IS NOT NULL
                 OR status IN ('CANCELLED', 'COMPLETED', 'NOT_COMPLETED', 'DECLINED')))
     OR (booking_source = 'APP'
            AND client_detached_at IS NULL
            AND client_id IS NOT NULL
            AND guest_name IS NULL
            AND guest_surname IS NULL
            AND guest_phone IS NULL
            AND cancel_token IS NULL)
     OR (booking_source = 'STAFF'
            AND client_detached_at IS NULL
            AND cancel_token IS NULL
            AND (
                (client_id IS NOT NULL
                    AND guest_name IS NULL
                    AND guest_surname IS NULL
                    AND guest_phone IS NULL)
             OR (client_id IS NULL
                    AND guest_name    IS NOT NULL AND btrim(guest_name)    <> ''
                    AND guest_surname IS NOT NULL AND btrim(guest_surname) <> ''
                    AND guest_phone IS NOT NULL)
            ))
     OR (client_detached_at IS NOT NULL
            AND client_id IS NULL
            AND guest_name IS NOT NULL AND btrim(guest_name) <> ''
            AND guest_surname IS NULL
            AND guest_phone IS NULL
            AND cancel_token IS NULL)
    ) NOT VALID;

COMMENT ON CONSTRAINT chk_bookings_guest_fields ON bookings IS
    'Phase 300 widens V137:145 with a fourth DETACHED arm. ATTACHED (any of the three source arms) '
    'and DETACHED are the only representable states; a half-detached row — client_id nulled without '
    'the sentinel and the stamp — is UNWRITABLE, which is what forces the service to detach in one '
    'UPDATE before deleting the users row. Added NOT VALID and validated in V163 (the V139 -> V140 '
    'precedent) to avoid an ACCESS EXCLUSIVE scan of the largest table in this file.';

-- ── 3. the same widening on the appointment header ────────────────────────────────
ALTER TABLE appointments DROP CONSTRAINT chk_appointment_guest_fields;

ALTER TABLE appointments
    ADD CONSTRAINT chk_appointment_guest_fields CHECK (
        (booking_source = 'LINK'
            AND client_detached_at IS NULL
            AND client_id IS NULL
            AND guest_name IS NOT NULL
            AND guest_phone IS NOT NULL
            AND (cancel_token IS NOT NULL
                 OR status IN ('CANCELLED', 'COMPLETED', 'NOT_COMPLETED', 'DECLINED')))
     OR (booking_source = 'APP'
            AND client_detached_at IS NULL
            AND client_id IS NOT NULL
            AND guest_name IS NULL
            AND guest_surname IS NULL
            AND guest_phone IS NULL
            AND cancel_token IS NULL)
     OR (booking_source = 'STAFF'
            AND client_detached_at IS NULL
            AND cancel_token IS NULL
            AND (
                (client_id IS NOT NULL
                    AND guest_name IS NULL
                    AND guest_surname IS NULL
                    AND guest_phone IS NULL)
             OR (client_id IS NULL
                    AND guest_name    IS NOT NULL AND btrim(guest_name)    <> ''
                    AND guest_surname IS NOT NULL AND btrim(guest_surname) <> ''
                    AND guest_phone IS NOT NULL)
            ))
     OR (client_detached_at IS NOT NULL
            AND client_id IS NULL
            AND guest_name IS NOT NULL AND btrim(guest_name) <> ''
            AND guest_surname IS NULL
            AND guest_phone IS NULL
            AND cancel_token IS NULL)
    ) NOT VALID;

COMMENT ON CONSTRAINT chk_appointment_guest_fields ON appointments IS
    'Phase 300 widens V139:100 with the same fourth DETACHED arm as chk_bookings_guest_fields. '
    'Header and children must never diverge on this rule.';

-- ── 4. reviews.client_id becomes nullable ─────────────────────────────────────────
-- No snapshot column: D3 mandates a SENTINEL, not the name, so there is nothing to store.
-- reviews carries no guest columns and no CHECK, so here the FK's SET NULL genuinely does
-- the work unaided and no application-side detach statement is required.
ALTER TABLE reviews ALTER COLUMN client_id DROP NOT NULL;

-- ── 5. the three FK relaxations ───────────────────────────────────────────────────
-- All three were declared inline (`client_id UUID [NOT NULL] REFERENCES users(id)`), so Postgres
-- auto-named them. Resolve the name from the catalog rather than hardcoding: an environment
-- restored through a different tool can carry a different auto-name, and a hardcoded DROP would
-- crash-loop Flyway there. (V157:96-120 is the template being copied.)
DO $$
DECLARE
    t   TEXT;
    fk  TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['bookings', 'appointments', 'reviews'] LOOP
        SELECT con.conname INTO fk
          FROM pg_constraint con
          JOIN pg_class rel      ON rel.oid = con.conrelid
          JOIN pg_attribute att  ON att.attrelid = con.conrelid AND att.attnum = con.conkey[1]
         WHERE rel.relname = t
           AND con.contype = 'f'
           AND array_length(con.conkey, 1) = 1
           AND att.attname = 'client_id';

        IF fk IS NULL THEN
            RAISE EXCEPTION 'V162: no single-column FK found on %.client_id — refusing to continue', t;
        END IF;

        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', t, fk);
    END LOOP;
END $$;

ALTER TABLE bookings
    ADD CONSTRAINT fk_bookings_client
        FOREIGN KEY (client_id) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE appointments
    ADD CONSTRAINT fk_appointments_client
        FOREIGN KEY (client_id) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE reviews
    ADD CONSTRAINT fk_reviews_client
        FOREIGN KEY (client_id) REFERENCES users(id) ON DELETE SET NULL;

COMMENT ON CONSTRAINT fk_bookings_client ON bookings IS
    'Phase 300. Was V18:5''s inline REFERENCES users(id) with NO on-delete clause (NO ACTION), one of '
    'the four constraints blocking a CLIENT self-delete. NOTE the SET NULL cannot perform a detach on '
    'its own: it writes one column, and Postgres enforces CHECK constraints on the UPDATE it performs, '
    'so the row would satisfy no arm of chk_bookings_guest_fields and the DELETE would abort. The '
    'service must null client_id, write the sentinel and stamp client_detached_at in ONE UPDATE, then '
    'delete the user. See this file''s header block. Do NOT revert to NO ACTION.';

COMMENT ON CONSTRAINT fk_appointments_client ON appointments IS
    'Phase 300. Mirror of fk_bookings_client for the visit header. Same one-UPDATE detach rule.';

COMMENT ON CONSTRAINT fk_reviews_client ON reviews IS
    'Phase 300 D3. reviews rows SURVIVE a client self-delete with their rating and comment intact so '
    'provider avg_rating / review_count never move; only the authorship is severed, and the author is '
    'rendered as a sentinel (never the real name) by ReviewResponse and SalonReviewResponse. Unlike '
    'bookings this FK needs no companion UPDATE — reviews has no guest columns and no CHECK.';
