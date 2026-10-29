-- non-destructive
-- Reason: validates the three V221 NOT VALID CHECKs, which every existing row satisfies (V221 only added the REVIEW_ABOUT_YOU_REMOVED literal and its branch).
-- Phase 415 follow-up — VALIDATE half of the V221 NOT VALID / VALIDATE split (V219 + V220 house style).
-- VALIDATE CONSTRAINT takes SHARE UPDATE EXCLUSIVE on in_app_notification: the scan blocks neither readers nor
-- writers. Each statement is its own scan.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE in_app_notification VALIDATE CONSTRAINT in_app_notification_type_chk;
ALTER TABLE in_app_notification VALIDATE CONSTRAINT in_app_notification_shape_chk;
ALTER TABLE in_app_notification VALIDATE CONSTRAINT in_app_notification_dedup_key_chk;
