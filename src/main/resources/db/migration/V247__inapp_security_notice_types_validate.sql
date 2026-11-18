-- non-destructive
-- Reason: validates the three V246 NOT VALID CHECKs, which every existing row satisfies (V246 only added the SECURITY_* literals and branches).
-- Phase B516 — VALIDATE half of the V246 NOT VALID / VALIDATE split (V241 + V242 house style).
-- VALIDATE CONSTRAINT takes SHARE UPDATE EXCLUSIVE on in_app_notification: the scan blocks neither readers nor
-- writers. Each statement is its own scan.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE in_app_notification VALIDATE CONSTRAINT in_app_notification_type_chk;
ALTER TABLE in_app_notification VALIDATE CONSTRAINT in_app_notification_shape_chk;
ALTER TABLE in_app_notification VALIDATE CONSTRAINT in_app_notification_dedup_key_chk;
