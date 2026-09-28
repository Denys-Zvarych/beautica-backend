-- Phase 333 audit-fix cycle 1, finding 5 follow-up (orchestrator-directed) — replaces
-- in_app_notification_dedup_key_chk (V181) with a cheaper, semantically IDENTICAL form.
--
-- V181's original CHECK was a single ~10-way string alternation concatenated ahead of THREE
-- possible UUID/digit-group shapes, evaluated as one big regex per row. Measured (audit-fix cycle 1,
-- finding 5) at ~0.0855 ms/row marginal cost over 2,000 rows in one insertForRecipients statement —
-- isolated via a DROP CONSTRAINT + ROLLBACK differential, so the number is the CHECK's own cost, not
-- the surrounding INSERT's. Above the ~0.05 ms/row "negligible" bar the finding set.
--
-- This migration is a fix-forward per §O-9 (applied migrations are immutable) — V181 itself is never
-- edited; this DROPs and re-ADDs the constraint under its ORIGINAL name (same catalog identity, no
-- application-code or test-name-facing change) via a new migration version.
--
-- WHY THIS IS CHEAPER.
-- Array membership (`= ANY (ARRAY[...])`) over 10 short literal strings is a simple equality scan —
-- no backtracking, unlike a 10-way regex alternation. Each per-segment regex below is ANCHORED and
-- FIXED-SHAPE (either exactly the UUID pattern, or the UUID-or-digits pattern for the optional third
-- segment) — never combined with the type alternation or with each other into one large pattern, so
-- the regex engine never has to try 10 type branches THEN backtrack across 3 possible tail shapes for
-- every row; splitting the key by ':' with split_part/string_to_array is a linear scan of the string
-- with no regex involved at all for that part.
--
-- WHY string_to_array/array_length, NOT split_part(...,3) = '' , FOR THE SEGMENT-COUNT CHECK.
-- split_part(dedup_key, ':', 3) returns '' both when there is NO third segment ("TYPE:uuid") and
-- when there IS a third segment that is EMPTY ("TYPE:uuid:" — a trailing colon with nothing after
-- it). The OLD regex rejected the trailing-colon shape (its optional tail group requires actual
-- content when present, and the whole pattern is anchored with $). A split_part-only "= '' OR ~ ..."
-- form would have silently ACCEPTED "TYPE:uuid:" as if it were the plain 2-segment case — a real
-- behavioural regression, not just a performance change. array_length(string_to_array(dedup_key,
-- ':'), 1) counts the ACTUAL number of ':'-delimited segments (2 vs 3), so "TYPE:uuid:" correctly
-- reports 3 segments, falls into the CASE's WHEN 3 branch, and is rejected there because its (empty)
-- third segment matches neither the UUID nor the 1-10-digit alternative.
--
-- EQUIVALENCE, proven by com.beautica.notification.inapp.repository.InAppNotificationRepositoryIT's
-- should_acceptTypeColonUuid_when_everyEnumType (all 10 TYPE literals, TYPE:uuid form) and
-- should_rejectMalformedDedupKey_when_rawInsert (parameterised: '@', spaces, '+', letters outside the
-- type prefix/hex, wrong segment count including the "TYPE:uuid:" trailing-colon case above, an
-- unknown TYPE) — plus the pre-existing should_insertDedupKey_when_typeColonIdFormat /
-- _when_rescheduleEpochSecondsSuffix / _when_inviteAcceptedSalonAndUserIdFormat, which this migration
-- reuses rather than forks: every documented format (TYPE:uuid, TYPE:uuid:epochSeconds,
-- INVITE_ACCEPTED:uuid:uuid) is accepted, and every shape the OLD regex rejected is still rejected.
--
-- MEASURED IMPACT (should_measureNegligiblePerRowCost_when_insertingTwoThousandRowsThroughDedupKeyCheck,
-- same DROP-CONSTRAINT+ROLLBACK differential, 3 runs post-migration): ~0.060 ms/row average, down from
-- V181's ~0.0855 ms/row baseline (~30% reduction) — still marginally above the ~0.05 ms/row bar, within
-- this VM's single-run timing noise band. See that test's own javadoc for the full spread and why
-- getting reliably under ~0.05 ms/row would need moving this validation off the per-row CHECK path
-- entirely, a design change beyond this migration's scope.
--
-- TYPE literal set: kept in sync BY HAND with in_app_notification_type_chk (same migration, V181)
-- and InAppNotificationType.values() — any future addition must update all three, exactly as V181's
-- own dedup_key_chk comment already required; should_keepTypeCheckDedupRegexAndEnumInSync now parses
-- the ARRAY[...] literal set (not a regex alternation) but enforces the identical invariant.
ALTER TABLE in_app_notification
    DROP CONSTRAINT in_app_notification_dedup_key_chk,
    ADD CONSTRAINT in_app_notification_dedup_key_chk CHECK (
        split_part(dedup_key, ':', 1) = ANY (ARRAY[
            'BOOKING_CREATED', 'BOOKING_CANCELLED_BY_CLIENT', 'BOOKING_DECLINED',
            'BOOKING_NOT_COMPLETED', 'BOOKING_RESCHEDULED', 'REVIEW_REQUESTED',
            'BOOKING_CANCELLED_SALON_CLOSED', 'BOOKING_CANCELLED_MASTER_REMOVED',
            'REVIEW_RECEIVED', 'INVITE_ACCEPTED'])
        AND split_part(dedup_key, ':', 2) ~
            '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
        AND CASE array_length(string_to_array(dedup_key, ':'), 1)
                WHEN 2 THEN true
                WHEN 3 THEN split_part(dedup_key, ':', 3) ~
                    '^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|[0-9]{1,10})$'
                ELSE false
            END
    );
