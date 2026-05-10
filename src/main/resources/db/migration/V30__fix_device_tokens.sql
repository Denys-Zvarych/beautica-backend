-- Drop redundant single-column index; UNIQUE(user_id, token) already covers user_id prefix scans.
DROP INDEX IF EXISTS idx_device_tokens_user_id;

-- Add DEFAULT now() so INSERTs that omit created_at succeed instead of violating NOT NULL.
ALTER TABLE device_tokens ALTER COLUMN created_at SET DEFAULT now();

-- Add soft-delete + TTL support: is_active for revocation, updated_at for stale-token eviction.
ALTER TABLE device_tokens
    ADD COLUMN is_active  BOOLEAN     NOT NULL DEFAULT true,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
