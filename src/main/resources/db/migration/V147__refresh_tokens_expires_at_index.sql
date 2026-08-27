-- Track token-lifetime-and-reuse-detection perf — index the refresh-token cleanup sweep predicate.
--
-- RefreshTokenCleanupJob runs a daily bounded hard-delete via
-- RefreshTokenRepository.deleteAllByExpiresAtBefore:
--   DELETE FROM refresh_tokens WHERE expires_at < :cutoff
-- V16 only created a plain index on `user_id`, and V146 added one on `family_id`, so this sweep
-- falls back to a full sequential scan that worsens as the table grows (every rotation INSERTs a
-- new row; only the predecessor is revoked, never deleted — see RefreshTokenCleanupJob's javadoc).
-- A single-column B-tree on expires_at lets the planner range-scan only the dead rows. Mirrors
-- V100__add_password_reset_tokens_expires_at_index.sql, which fixed the identical problem for the
-- sibling password_reset_tokens cleanup sweep.
--
-- Steady-state scale: a row lives 30d TTL + 7d retention = 37 days; at ~5,000 active users
-- rotating ~3x/day that is roughly 555,000 rows by the time this index ships — well past the
-- point a sequential scan is acceptable, and the first-ever sweep scans the entire
-- never-before-reaped history in one pass.
--
-- Plain CREATE INDEX, matching V100's precedent: refresh_tokens is still pre-launch scale (same
-- table V146's header records as small enough for a single-transaction ACCESS EXCLUSIVE DDL). If
-- this migration is ever re-run against a large, populated table, switch to CREATE INDEX
-- CONCURRENTLY (run outside a transaction) to avoid holding an ACCESS EXCLUSIVE lock for the
-- duration of the index build.

CREATE INDEX idx_refresh_tokens_expires_at
    ON refresh_tokens (expires_at);
