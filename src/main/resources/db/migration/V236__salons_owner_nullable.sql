-- non-destructive
-- Reason: the CHECK only constrains rows with owner_id NULL, which no earlier app version can write (owner_id was NOT NULL until this migration), so a rollback image is unaffected.
-- V236 — salons.owner_id becomes nullable for a deleted owner's detached salons (Phase 488, SUP-67 / SAL-47).
--
-- WHY
-- A SALON_OWNER with no ACTIVE salon may now delete the account (DELETE /api/v1/users/me, SUP-11). The
-- deactivated salons are NOT deleted (SAL-D05 stays locked; D2 answered 2026-11-12: keep the history
-- anonymous): OwnerAccountSelfDeletionService detaches them — owner_id = NULL, phone/instagram_url blanked
-- — so clients keep their bookings, reviews and client_reviews with the salon name. V3 declared owner_id
-- NOT NULL REFERENCES users(id) (NO ACTION), so the users delete would otherwise abort.
--
-- INVARIANT: an ownerless salon can never be active. chk_salons_ownerless_inactive pins it in the DB, so every
-- active-salon read path may keep treating the owner as present. is_active is NOT NULL since V3 (a CHECK
-- passes on NULL — the NOT NULL is what makes this CHECK total).
--
-- SHAPE (V114/V115 discipline): the CHECK is added NOT VALID here (metadata-only, enforced on every new
-- INSERT/UPDATE from this commit) and VALIDATEd in V237, across a transaction boundary, so the scan runs
-- under SHARE UPDATE EXCLUSIVE rather than inside this migration's ACCESS EXCLUSIVE. Every existing row has
-- owner_id NOT NULL (the column was NOT NULL until this statement), so the validation cannot fail.
--
-- Forward-only, fix-forward (Anti-Bug §O-9): new version, max existing = V235. No data is changed.

-- ── Lock guard (must precede the DDL) ───────────────────────────────────────────────────────
-- Both statements are catalog-only once granted (DROP NOT NULL does not scan; ADD CONSTRAINT ... NOT VALID
-- does not scan), but each needs ACCESS EXCLUSIVE on `salons`, read on every salon/booking page. A queued
-- ACCESS EXCLUSIVE request blocks every later reader (FIFO), so fail fast after 5s — the same value and
-- argument as V118/V119/V121/V128/V137/V138 — and let Flyway roll back and retry on the next deploy.
-- SET LOCAL: scoped to Flyway's per-migration transaction, never leaks to the pooled connection.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '1min';

ALTER TABLE salons ALTER COLUMN owner_id DROP NOT NULL;

ALTER TABLE salons
    ADD CONSTRAINT chk_salons_ownerless_inactive
    CHECK (owner_id IS NOT NULL OR is_active = false) NOT VALID;

COMMENT ON CONSTRAINT chk_salons_ownerless_inactive ON salons IS
    'Phase 488 (SAL-47). owner_id is NULL only on a salon detached by its owner''s account deletion, '
    'which is always deactivated first. An ownerless salon can never be active.';
