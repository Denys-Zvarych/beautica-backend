-- Denormalized cache of the R2 public URL for the user's avatar; source of truth remains
-- users.avatar_r2_key + R2StorageService.buildPublicUrl(). Search service already has a
-- CAST(NULL AS TEXT) AS avatar_url placeholder waiting on this column (backlog Phase 6.7 INFO).
ALTER TABLE users ADD COLUMN avatar_url VARCHAR(1000);
