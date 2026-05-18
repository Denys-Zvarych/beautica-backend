-- Email verification on registration (phase-1.1).
-- Inline columns on users — no separate OTP table: the code is single-use,
-- short-lived (15 min), and 1:1 with the user, so a side table would only
-- add a join with no lifecycle or volume benefit.
--
-- No index on verification_code or email_verified: OTP lookup is always
-- scoped to a specific user identified by email (UNIQUE, already indexed),
-- so a secondary index on these columns provides no query benefit and would
-- only add write overhead on every registration and verification event.

ALTER TABLE users
    ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE users
    ADD COLUMN verification_code VARCHAR(6);

ALTER TABLE users
    ADD COLUMN verification_code_expires_at TIMESTAMPTZ;
