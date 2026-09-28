-- Phase 332 — in-app notification feed schema.
--
-- A per-user, PII-free feed table: WHAT happened to WHICH booking/visit, FOR WHOM. No copy, names,
-- prices or notes are ever stored here — render text is produced by the mobile client from ARB keys
-- and display params are resolved at READ TIME (phase 334) from live rows the reader is already
-- allowed to see. See CLAUDE.md "Booking notes" rule: clientComment/providerComment/cancellation
-- notes must never reach this table.
--
-- created_at DEFAULT now() (§O-1) so a raw-SQL insert (fixtures, the native
-- insertIgnoringDuplicate write path in phase 333) never fails NOT NULL without an explicit value;
-- the JPA entity also supplies it via @CreationTimestamp for the ORM path, in addition to (not
-- instead of) this DB default.
--
-- type is NOT NULL and is the ONLY plain-nullability-checked column in chk — every other column is
-- nullable; in_app_notification_shape_chk below is the constraint that makes those nullable columns
-- meaningful (a CHECK over a nullable column alone is vacuous — memory: "Postgres CHECK passes on
-- NULL" — so the shape check is written per §N-8 to be structurally non-vacuous for every type it
-- covers; see its own comment for the one deliberate exception).
--
-- appointment_id is a genuine FK to appointments(id), ON DELETE CASCADE, mirroring booking_id: the
-- doc's draft SQL omitted the REFERENCES clause pending verification of the real table (it exists,
-- V124__create_appointments.sql, UUID PK) — adding the FK here keeps the row from ever dangling on
-- an appointment id that no longer exists, exactly like booking_id.
--
-- subject_user_id is ON DELETE SET NULL, not CASCADE (audit-fix cycle 1, finding 7): this FK points
-- at the NEW TEAMMATE named in an INVITE_ACCEPTED row, whose recipient is a DIFFERENT user (the
-- owner/admin who gets notified). CASCADE here would delete THAT recipient's feed row the moment the
-- teammate self-deletes their own account (phase 337/338) — an unrelated user's history vanishing as
-- a side effect of someone else's deletion. SET NULL keeps the row (recipient still sees "a teammate
-- joined"), matching salon_id's existing ON DELETE SET NULL for the same reason.
CREATE TABLE in_app_notification (
    id                 UUID PRIMARY KEY,
    recipient_user_id  UUID        NOT NULL REFERENCES users(id)       ON DELETE CASCADE,
    type               VARCHAR(40) NOT NULL,
    booking_id         UUID        NULL     REFERENCES bookings(id)    ON DELETE CASCADE,
    appointment_id     UUID        NULL     REFERENCES appointments(id) ON DELETE CASCADE,
    salon_id           UUID        NULL     REFERENCES salons(id)      ON DELETE SET NULL,
    subject_user_id    UUID        NULL     REFERENCES users(id)       ON DELETE SET NULL, -- the new teammate (INVITE_ACCEPTED)
    dedup_key          VARCHAR(160) NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at            TIMESTAMPTZ NULL,
    CONSTRAINT in_app_notification_type_chk CHECK (type IN (
        'BOOKING_CREATED','BOOKING_CANCELLED_BY_CLIENT','BOOKING_DECLINED',
        'BOOKING_NOT_COMPLETED','BOOKING_RESCHEDULED','REVIEW_REQUESTED',
        'BOOKING_CANCELLED_SALON_CLOSED','BOOKING_CANCELLED_MASTER_REMOVED','REVIEW_RECEIVED',
        'INVITE_ACCEPTED')),
    -- §N-8 polymorphic shape CHECK, mapped from phase-333's event -> recipient matrix.
    --
    -- The 9 booking/review types (everything except INVITE_ACCEPTED) all carry
    -- dedup_key = '<TYPE>:<appointmentId|bookingId>[:<suffix>]', i.e. every one of them is ABOUT one
    -- booking or one visit, so each requires booking_id IS NOT NULL OR appointment_id IS NOT NULL.
    -- This stays valid for the row's entire life: both FKs are ON DELETE CASCADE, so the underlying
    -- booking/appointment disappearing deletes THIS ROW too rather than nulling the column — there is
    -- no post-delete state where the row survives with both columns null (§O-8 "Booking-type rows...
    -- those CASCADE-delete, so no post-delete violation").
    --
    -- INVITE_ACCEPTED is deliberately given TRUE (no shape requirement) rather than
    -- "salon_id IS NOT NULL OR subject_user_id IS NOT NULL". Reasoning: unlike booking_id/
    -- appointment_id, salon_id and subject_user_id are each ON DELETE SET NULL, not CASCADE — the
    -- row survives its FK target's deletion with the column nulled instead of the row disappearing.
    -- Both FKs point at independent entities (a salon, a teammate user) that can be deleted at
    -- unrelated times, so a single row CAN end up with both columns null (teammate self-deletes,
    -- then — separately — the salon is later deleted, or vice versa). If the CHECK required
    -- "at least one of the two", the SECOND SET NULL would re-validate the CHECK on that UPDATE and
    -- fail, which would abort the salon/user deletion transaction itself — turning a notification
    -- schema constraint into an outage in an unrelated deletion flow. A CHECK that isn't satisfiable
    -- for the FULL lifetime of every row it applies to is worse than no CHECK, so INVITE_ACCEPTED is
    -- exempted; correctness for this type is enforced at the phase-333 write-path layer instead
    -- (both columns ARE always set together at insert time, per the matrix).
    CONSTRAINT in_app_notification_shape_chk CHECK (
        CASE type
            WHEN 'INVITE_ACCEPTED' THEN TRUE
            ELSE booking_id IS NOT NULL OR appointment_id IS NOT NULL
        END
    ),
    -- §A / finding 5 (security) — dedup_key is an internal idempotency token, never free text, so a
    -- DB-level shape CHECK closes off ever storing PII (email/name/phone) in it even if a future
    -- caller passes the wrong string. Restricted to exactly phase-333's documented formats:
    --   '<TYPE>:<id>'                       -- the common case (booking or appointment id)
    --   '<TYPE>:<id>:<epochSeconds>'        -- BOOKING_RESCHEDULED's new-start-time suffix
    --   '<TYPE>:<id>:<id>'                  -- INVITE_ACCEPTED's 'INVITE_ACCEPTED:<salonId>:<newMemberUserId>'
    -- TYPE is restricted to the same 10-value set as the type column (kept in sync by hand — any
    -- addition to in_app_notification_type_chk's list must add the same literal here). No '@', no
    -- whitespace, no letters beyond the type prefix and UUID hex digits can ever match.
    CONSTRAINT in_app_notification_dedup_key_chk CHECK (
        dedup_key ~ ('^(BOOKING_CREATED|BOOKING_CANCELLED_BY_CLIENT|BOOKING_DECLINED|' ||
                      'BOOKING_NOT_COMPLETED|BOOKING_RESCHEDULED|REVIEW_REQUESTED|' ||
                      'BOOKING_CANCELLED_SALON_CLOSED|BOOKING_CANCELLED_MASTER_REMOVED|' ||
                      'REVIEW_RECEIVED|INVITE_ACCEPTED):' ||
                      '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}' ||
                      '(:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}' ||
                      '|[0-9]{1,10}))?$')
    ),
    CONSTRAINT in_app_notification_dedup_uq UNIQUE (recipient_user_id, dedup_key)
);

-- Feed pagination — recipient's rows newest-first; id DESC breaks created_at ties deterministically
-- (matches findByRecipientUserIdOrderByCreatedAtDescIdDesc).
CREATE INDEX in_app_notification_feed_idx
    ON in_app_notification (recipient_user_id, created_at DESC, id DESC);

-- Unread-count / bell-red-dot lookup (countByRecipientUserIdAndReadAtIsNull). Partial: only unread
-- rows are ever queried by this predicate, so an unqualified index would waste writes on every row
-- that gets marked read (§E-5).
CREATE INDEX in_app_notification_unread_idx
    ON in_app_notification (recipient_user_id) WHERE read_at IS NULL;

-- Retention sweep (phase 335) — deleteCreatedBefore scans by created_at across all recipients.
CREATE INDEX in_app_notification_created_idx ON in_app_notification (created_at);

-- §E-3/§O-6 partial FK-support indexes (audit-fix cycle 1, findings 1-4). Users are hard-deleted in
-- prod, so recipient/subject FK actions and any future admin lookup by booking/appointment/salon
-- would otherwise seq-scan this table. Each is partial on "column IS NOT NULL" because the column is
-- null on the large majority of rows (only the types that carry that particular id ever populate it),
-- matching the existing in_app_notification_unread_idx convention (§E-5).
CREATE INDEX in_app_notification_subject_idx
    ON in_app_notification (subject_user_id) WHERE subject_user_id IS NOT NULL;

CREATE INDEX in_app_notification_booking_idx
    ON in_app_notification (booking_id) WHERE booking_id IS NOT NULL;

CREATE INDEX in_app_notification_appointment_idx
    ON in_app_notification (appointment_id) WHERE appointment_id IS NOT NULL;

CREATE INDEX in_app_notification_salon_idx
    ON in_app_notification (salon_id) WHERE salon_id IS NOT NULL;

-- §B / audit-fix cycle 2, finding 1 (security) — in_app_notification_shape_chk deliberately allows
-- INVITE_ACCEPTED rows to reach both salon_id IS NULL and subject_user_id IS NULL, but only as the
-- END STATE of two independent, later SET NULL cascades (see that constraint's comment). Nothing so
-- far stops an INSERT from starting in that state on day one, which a CHECK cannot express: a CHECK
-- has no event granularity ("true for UPDATE, false for INSERT" is not a CHECK-expressible predicate),
-- so closing the gap needs a trigger.
--
-- BEFORE INSERT only, on purpose — never BEFORE UPDATE. Firing on UPDATE would re-validate on the
-- exact SET NULL cascades in_app_notification_shape_chk was written to survive, turning this
-- insert-time guard into the same outage its comment rules out: aborting an unrelated salon/user
-- deletion transaction because it happened to leave this row's last non-null column null. This
-- trigger is deliberately narrower in scope than the CHECK it complements.
--
-- Interaction with insertIgnoringDuplicate's ON CONFLICT DO NOTHING (phase 333): Postgres fires
-- BEFORE INSERT ROW triggers before conflict detection, so an invalid INVITE_ACCEPTED row raises
-- here even on what would otherwise be a suppressed duplicate. Accepted: the write path never
-- intentionally sends both columns null, so this can only fire on a bug, and a bug surfacing loudly
-- (an exception) beats being silently swallowed by the dedup path.
CREATE FUNCTION in_app_notification_reject_empty_invite_accepted() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.type = 'INVITE_ACCEPTED'
       AND NEW.salon_id IS NULL
       AND NEW.subject_user_id IS NULL THEN
        RAISE EXCEPTION
            'in_app_notification: INVITE_ACCEPTED row (id=%) must have salon_id or subject_user_id set on insert',
            NEW.id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER in_app_notification_reject_empty_invite_accepted_trg
    BEFORE INSERT ON in_app_notification
    FOR EACH ROW
    EXECUTE FUNCTION in_app_notification_reject_empty_invite_accepted();
