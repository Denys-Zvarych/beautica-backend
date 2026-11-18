-- non-destructive
-- Reason: validates the V253 NOT VALID dedup-key CHECK, which every existing row satisfies (V253 only added the BOOKING_REMINDER ':H2' 4-segment branch).
-- Phase B522 — VALIDATE half of the V253 NOT VALID / VALIDATE split (V246 + V247 house style).
-- VALIDATE CONSTRAINT takes SHARE UPDATE EXCLUSIVE on in_app_notification: the scan blocks neither readers nor
-- writers.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE in_app_notification VALIDATE CONSTRAINT in_app_notification_dedup_key_chk;
