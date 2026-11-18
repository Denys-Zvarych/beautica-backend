-- non-destructive
-- Reason: validates the V249 NOT VALID CHECK, which every existing row satisfies (masked_contact is NULL on all rows written before V249).
-- Phase B516 audit-fix cycle 2 — VALIDATE half of the V249 NOT VALID / VALIDATE split (V246 + V247 house style).
-- VALIDATE CONSTRAINT takes SHARE UPDATE EXCLUSIVE on in_app_notification: the scan blocks neither readers nor writers.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE in_app_notification VALIDATE CONSTRAINT in_app_notification_masked_contact_chk;
