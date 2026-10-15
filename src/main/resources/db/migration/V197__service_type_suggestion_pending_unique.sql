-- Concurrent-submit race on POST /service-types/suggest: the service checks
-- existsPendingByCategoryNameAndSuggestedNameIgnoreCase(...) and then inserts, but V76 backed that
-- check with a NON-unique index (idx_service_type_suggestion_dedup), so two concurrent «Надіслати»
-- taps could both pass the check and both insert a PENDING row. Fix-forward (§O-9): enforce the
-- dedup key in the database with a PARTIAL UNIQUE index.
--
-- Key mirrors the service-layer check EXACTLY: status = PENDING AND category_name = ? AND
-- LOWER(suggested_name) = LOWER(?). No trim — the service does not trim either. Both key columns
-- are NOT NULL (V76), so no NULL-passes-uniqueness hole.
--
-- platform_categories needs no equivalent: uq_platform_categories_name UNIQUE(name) (V64) already
-- blocks a second row with the same name in ANY status.
--
-- ── Why two migrations, and why this lock ──────────────────────────────────────────────────
-- * LOCK ... EXCLUSIVE (held until this migration's transaction commits). During a rolling deploy
--   the still-running OLD instance must not write a duplicate PENDING row between the collapse
--   UPDATE and CREATE UNIQUE INDEX (that would abort the index build and the boot). EXCLUSIVE
--   conflicts with ROW EXCLUSIVE (INSERT/UPDATE) AND with ROW SHARE — the SELECT ... FOR UPDATE in
--   ServiceTypeSuggestionRepository.findByTokenHashForUpdate on the admin approve/reject path.
--   SHARE ROW EXCLUSIVE would admit that ROW SHARE, letting the admin hold a row lock that this
--   UPDATE then waits on while the admin's own UPDATE waits on our table lock: a deadlock.
--   EXCLUSIVE still does NOT conflict with ACCESS SHARE, so plain reads (review page, exists-check)
--   keep working; old-instance writes and locking reads simply wait milliseconds.
-- * SET LOCAL lock_timeout bounds the WAIT for that lock (a long-open transaction fails the boot
--   fast and the deploy retries instead of hanging); statement_timeout bounds the work while held.
--   Both are LOCAL so neither leaks onto the pooled connection (V159 header; never copy V83's bare
--   SET). Explicit here, it also overrides the 3 s ceiling V83 leaks under the test profile.
-- * The old non-unique idx_service_type_suggestion_dedup is NOT dropped here: DROP INDEX takes
--   ACCESS EXCLUSIVE, which would then be held for the rest of this transaction and block reads.
--   V198 drops it in its own short transaction. Between V197 and V198 both indexes exist; the old
--   one is merely a redundant write for that window.
-- ───────────────────────────────────────────────────────────────────────────────────────────

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '1min';

LOCK TABLE service_type_suggestion IN EXCLUSIVE MODE;

-- 1. Collapse pre-existing duplicate PENDING rows: keep the oldest (created_at, then id as a
--    deterministic tie-break), close the newer ones as REJECTED. The token is cleared because
--    chk_service_type_suggestion_token_pending only allows a token on PENDING rows. decision_reason
--    stays NULL; no in-app notification row is created (that only happens in the service), so the
--    requester is not told about this housekeeping. On a clean DB this updates nothing.
UPDATE service_type_suggestion s
   SET status           = 'REJECTED',
       decided_at       = NOW(),
       token_hash       = NULL,
       token_expires_at = NULL
 WHERE s.status = 'PENDING'
   AND EXISTS (
        SELECT 1
          FROM service_type_suggestion o
         WHERE o.status = 'PENDING'
           AND o.category_name = s.category_name
           AND lower(o.suggested_name) = lower(s.suggested_name)
           AND (o.created_at, o.id) < (s.created_at, s.id)
   );

-- 2. The unique index on the same expression + predicate as the service check. The name is
--    referenced by ServiceTypeSuggestionService.PENDING_DEDUP_INDEX to translate the violation to
--    the same 409 the exists-check throws.
CREATE UNIQUE INDEX ux_service_type_suggestion_pending_dedup
    ON service_type_suggestion (category_name, lower(suggested_name))
    WHERE status = 'PENDING';
