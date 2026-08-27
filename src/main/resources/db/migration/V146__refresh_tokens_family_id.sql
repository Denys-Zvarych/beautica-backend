-- Reuse-detection support: a refresh token "family" is the chain of tokens produced by
-- successive rotations from a single login/register/invite-accept event. Presenting an
-- already-revoked token means that chain was compromised, so the whole family must be
-- revocable in one statement (see RefreshTokenRepository.revokeAllByFamilyId).
--
-- Lock-pattern trade-off (recorded, not a TODO): this migration runs ADD COLUMN -> backfill
-- -> SET NOT NULL -> CREATE INDEX inside ONE Flyway transaction, holding ACCESS EXCLUSIVE on
-- refresh_tokens for its full duration. A perf audit flagged that as HIGH (a lock-safe form
-- would use CHECK ... NOT VALID + VALIDATE CONSTRAINT and CREATE INDEX CONCURRENTLY, outside a
-- single transaction). A security audit independently flagged the SAME single-transaction
-- property as a BENEFIT: it closes any window where a concurrent insert could land a row with
-- a null family_id between the backfill and the NOT NULL constraint. Both are correct — this is
-- a genuine trade-off, not a bug. Decision: keep this migration atomic as written. The table is
-- small pre-launch (Android/Firebase rollout hasn't started), so the exclusive lock is
-- milliseconds, and the atomic form's correctness guarantee is worth more here than the
-- (currently negligible) concurrency cost. Do NOT copy this pattern blindly against a table at
-- production scale — use the lock-safe CONCURRENTLY / NOT VALID form there instead.

-- Nullable first: an unbackfilled NOT NULL column would break every existing refresh in
-- production, where there are live sessions with rows already in this table.
ALTER TABLE refresh_tokens ADD COLUMN family_id UUID;

-- Backfill: every pre-existing token becomes its own single-token family. These rows were
-- each minted by the pre-family login/register code path, so there is no rotation history
-- to reconstruct — treating each as family-of-one is the only sound default. It also means
-- a replay of one of these legacy tokens revokes only itself, never a sibling; that is
-- correct, since no sibling relationship exists for them.
UPDATE refresh_tokens SET family_id = id WHERE family_id IS NULL;

-- NOT NULL after backfill, so every future row is required to declare a family and the
-- backfill above cannot be skipped by re-ordering a future migration ahead of this one.
ALTER TABLE refresh_tokens ALTER COLUMN family_id SET NOT NULL;

CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens(family_id);
