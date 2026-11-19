-- non-destructive
-- Reason: validates the three V256 NOT VALID CHECKs, which every existing row satisfies (V256 only added the SECURITY_PASSWORD_RESET literal and branch).
-- Phone-first hardening (AUTH-D26 a) — VALIDATE half of the V256 NOT VALID / VALIDATE split (V246 + V247 house style).
-- VALIDATE CONSTRAINT takes SHARE UPDATE EXCLUSIVE on in_app_notification: the scan blocks neither readers nor
-- writers. Each statement is its own scan.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE in_app_notification VALIDATE CONSTRAINT in_app_notification_type_chk;
ALTER TABLE in_app_notification VALIDATE CONSTRAINT in_app_notification_shape_chk;
ALTER TABLE in_app_notification VALIDATE CONSTRAINT in_app_notification_dedup_key_chk;
