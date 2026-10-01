SET LOCAL lock_timeout = '5s';

-- Phase 339 audit cycle 2 (perf P1) — follow-up to V185, which is left UNTOUCHED (it may already have
-- been applied on a dev DB, and editing an applied migration changes its checksum).
--
-- V185's header says "The (user_id, token) UNIQUE from V29 stays". That is stale/wrong once
-- ux_device_tokens_token exists:
--   * (user_id, token) uniqueness is strictly implied by token-only uniqueness, so V29's constraint is
--     redundant — it only costs an extra index write per insert/update; and
--   * it is NOT the ON CONFLICT (token) arbiter of DeviceTokenRepository#upsertToken, so two concurrent
--     upserts of the same token by the SAME user could trip THIS unique first and raise
--     unique_violation instead of resolving through the arbiter.
-- So drop it. V29's index supported user_id prefix scans, and V30 dropped the plain user_id index on the
-- strength of that constraint — so re-create idx_device_tokens_user_id: ON DELETE CASCADE from users
-- (and the user_id IN (...) bulk deletes) must find a user's rows without a seq scan.
--
-- The constraint was declared inline in V29 (unnamed), so Postgres named it
-- device_tokens_user_id_token_key; look it up by its column set rather than trusting the name.
DO $$
DECLARE
    con_name text;
BEGIN
    SELECT c.conname INTO con_name
      FROM pg_constraint c
     WHERE c.conrelid = 'device_tokens'::regclass
       AND c.contype = 'u'
       AND (SELECT array_agg(a.attname::text ORDER BY a.attname::text)
              FROM pg_attribute a
             WHERE a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)) = ARRAY['token', 'user_id'];
    IF con_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE device_tokens DROP CONSTRAINT %I', con_name);
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_device_tokens_user_id ON device_tokens (user_id);
