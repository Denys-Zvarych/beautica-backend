-- non-destructive
-- Reason: one nullable column added; no default, no rewrite, no existing row changes.
-- Phase B516 (AUTH-12, AUTH-15) — the 60 s resend cooldown of the password-reset SMS branch (an account without a
-- confirmed email). Stamped inside the transaction that holds the user row lock, so two concurrent requests cannot
-- both pass the cooldown: the SMS itself (phone_otps row) is written asynchronously after commit and cannot serve as
-- the cooldown source. NULL = no SMS code ever requested. No index: read only on the already-locked row.
SET LOCAL lock_timeout = '3s';

ALTER TABLE users ADD COLUMN password_reset_sms_sent_at TIMESTAMPTZ NULL;
