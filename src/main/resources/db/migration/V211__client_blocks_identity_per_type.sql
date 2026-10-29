-- non-destructive
-- Reason: client_blocks is new on this unreleased branch (V210) and no deployed app version reads or writes it; the UPDATE below normalises every row to the two new CHECKs before they are added.
-- Phase 397 audit-fix (cycle 1) — one identity per block row (user decision 2026-10-28, supersedes V210's D2/D5
-- phone part). Structural, so a new version: V210 is already applied on local dev DBs and stays untouched.
--
-- V210's header still says a registered client is blocked by "blocked_user_id ... and/or phone_hash" and that a
-- repeat block fills the missing key. That is no longer the model:
--   * CLIENT row  — blocked_user_id only. A registered user's profile phone is unverified, so it is never hashed or
--                   stored (no phone_hash, no phone_last4).
--   * GUEST row   — phone_hash (+ phone_last4) of the booking's OTP-verified guest phone; blocked_user_id NULL.
--   * Matching is per type, never across: an app booking matches CLIENT rows by user id, a guest booking matches
--     GUEST rows by phone hash. A guest phone equal to some client's profile phone links nothing.
--
-- 1) Normalise rows written under the V210 model: drop the profile-phone hash/last4 from CLIENT rows (a privacy
--    clean-up as much as a CHECK prerequisite). Rows filled across types by the retired fill-in become CLIENT rows.
-- 2) chk_client_blocks_identity_kind: exactly one of blocked_user_id / phone_hash. Strictly tighter than V210's
--    chk_client_blocks_blocked_identity (>= 1), which stays as a redundant floor. num_nonnulls is never NULL, so the
--    CHECK cannot pass vacuously on NULL input. It also encodes "a CLIENT row carries no phone_hash" — a separate
--    CHECK for that would only restate it.
-- 3) chk_client_blocks_last4_needs_hash: masked last digits only on a row that has the hash (i.e. a GUEST row).
--    Never NULL either: both operands are IS [NOT] NULL tests.

-- UPDATE takes ROW EXCLUSIVE, ALTER TABLE ... ADD CHECK takes ACCESS EXCLUSIVE and scans: bound the wait and the
-- scan (same values as V206/V208/V209).
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

-- 1) normalise -------------------------------------------------------------------------------------------------
UPDATE client_blocks
   SET phone_hash = NULL,
       phone_last4 = NULL
 WHERE blocked_user_id IS NOT NULL
   AND (phone_hash IS NOT NULL OR phone_last4 IS NOT NULL);

-- 2) + 3) per-type identity ------------------------------------------------------------------------------------
ALTER TABLE client_blocks
    ADD CONSTRAINT chk_client_blocks_identity_kind CHECK (num_nonnulls(blocked_user_id, phone_hash) = 1),
    ADD CONSTRAINT chk_client_blocks_last4_needs_hash CHECK (phone_last4 IS NULL OR phone_hash IS NOT NULL);
