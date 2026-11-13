-- V237 — VALIDATE chk_salons_ownerless_inactive (added NOT VALID by V236, Phase 488).
--
-- Split from V236 across a transaction boundary (V114/V115 discipline): VALIDATE CONSTRAINT takes only
-- SHARE UPDATE EXCLUSIVE, so reads and writes on `salons` continue during the scan. Cannot fail: owner_id
-- was NOT NULL on every row until V236, and V236's NOT VALID constraint has enforced the predicate on every
-- write since. Forward-only (Anti-Bug §O-9).

-- lock_timeout bounds the wait for SHARE UPDATE EXCLUSIVE (conflicts only with DDL/VACUUM, so the wait is
-- normally zero); statement_timeout bounds the scan itself, which runs while holding the lock. Same values
-- as V138. SET LOCAL: never leaks to the pooled connection.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '1min';

ALTER TABLE salons VALIDATE CONSTRAINT chk_salons_ownerless_inactive;
