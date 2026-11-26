-- non-destructive
-- Reason: drops two cross-field CHECK constraints only; no column, index or row touched. The token columns stay
-- (expand/contract) and are dropped in a later release (phase 555) once B545 runs on every pod.
--
-- V266 — B545 (SVC-61): the operator's request emails are notification-only; email approve / reject is retired and
-- the portal (internal catalog API, by id) is the only decision path. The B545 entities no longer map
-- token_hash / token_expires_at, so the new code inserts rows with both NULL (the columns have been nullable since
-- V65 / V76) and never clears them on a decision.
--
-- WHY THE CHECKS GO — chk_platform_categories_token_pending / chk_service_type_suggestion_token_pending allow a token
-- only on a PENDING row. A request minted by a pre-B545 pod (token set) and then decided by a B545 pod (which does not
-- know the column and leaves it set) would violate the CHECK → 500 on approve / reject. Dropping both CHECKs lets the
-- leftover hash sit on a decided row; nothing reads it any more.
--
-- DEPLOY OVERLAP — during the Railway rollout an old pod still writes and reads the token columns; they are still
-- present, so it keeps working. Rolling back to a pre-B545 image after this migration is safe for the same reason
-- (it only loses the single-use CHECK, which its own code already enforces by clearing the token on decision).
--
-- LOCKING — DROP CONSTRAINT takes ACCESS EXCLUSIVE on each table but is catalog-only once granted; lock_timeout bounds
-- the wait (V138 / V260-V263 rationale). Idempotent: IF EXISTS on every statement.
SET LOCAL lock_timeout = '3s';

ALTER TABLE platform_categories
    DROP CONSTRAINT IF EXISTS chk_platform_categories_token_pending;

ALTER TABLE service_type_suggestion
    DROP CONSTRAINT IF EXISTS chk_service_type_suggestion_token_pending;
