-- Partial index for the hot query path: fetch active tokens by user.
-- The UNIQUE(user_id, token) index covers user_id prefix scans but includes inactive rows;
-- this partial index restricts to is_active = true, reducing rows scanned per user lookup.
CREATE INDEX idx_device_tokens_user_active
    ON device_tokens(user_id)
    WHERE is_active = true;
