-- Phase 339 (D10) — an FCM token identifies ONE physical install, so it may be bound to at most one
-- user at a time. V29's UNIQUE (user_id, token) let the same token sit under two users: a shared
-- device that switched accounts would then push user A's items to user B's session.
--
-- Step 1: de-duplicate by token, keeping the most recently touched row (updated_at, then id as a
-- deterministic tiebreak). Step 2: enforce uniqueness on token alone. The application upserts by
-- token on POST /api/v1/devices/token (reassigns to the caller, is_active = true).
--
-- The (user_id, token) UNIQUE from V29 stays — it backs the per-user idempotency pre-check.
DELETE FROM device_tokens d
 USING device_tokens newer
 WHERE d.token = newer.token
   AND d.id <> newer.id
   AND (newer.updated_at > d.updated_at
        OR (newer.updated_at = d.updated_at AND newer.id > d.id));

CREATE UNIQUE INDEX IF NOT EXISTS ux_device_tokens_token ON device_tokens (token);
