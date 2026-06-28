-- Phase 11.x performance — index the password-reset cleanup sweep predicate.
--
-- PasswordResetTokenCleanupJob runs a daily bounded hard-delete via
-- PasswordResetTokenRepository.deleteByExpiresAtBefore:
--   DELETE FROM password_reset_tokens WHERE expires_at < :cutoff
-- V55 only created a UNIQUE B-tree on `token` and a plain index on `user_id`, so
-- this sweep falls back to a full sequential scan that worsens as the table grows
-- (one row per forgot-password request until reclaimed). A single-column B-tree on
-- expires_at lets the planner range-scan only the dead rows.
--
-- Plain CREATE INDEX is fine here: password_reset_tokens is a pre-launch, small
-- table. If this migration is ever re-run against a large, populated table, switch
-- to CREATE INDEX CONCURRENTLY (run outside a transaction) to avoid holding an
-- ACCESS EXCLUSIVE lock for the duration of the index build.

CREATE INDEX idx_password_reset_tokens_expires_at
    ON password_reset_tokens (expires_at);
