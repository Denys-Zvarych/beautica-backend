-- Phase 3: add optional service photo URL to service_definitions.
-- The column is nullable — existing rows have no photo until one is explicitly set.
-- The HTTPS CHECK guard rejects plain http://, javascript:, data: and other
-- non-secure schemes at the DB layer as a second line of defence (anti-bug §O rule 2).
-- length >= 11 blocks bare-scheme submissions like "https:// " (8 chars for scheme +
-- at least 3 chars for a minimal host ensures a real URL is present).
ALTER TABLE service_definitions
    ADD COLUMN IF NOT EXISTS photo_url VARCHAR(2048)
        CHECK (photo_url IS NULL OR (photo_url LIKE 'https://%' AND length(photo_url) >= 11));
