-- non-destructive
-- Reason: validates the three V212 CHECKs; every pre-V212 row is source 'APP' (DEFAULT) with guest_display_name NULL, which satisfies all three.
-- Phase 399 audit — VALIDATE half of the V212 NOT VALID / VALIDATE split (V113 / V139 + V140 house style).
-- VALIDATE CONSTRAINT takes SHARE UPDATE EXCLUSIVE on reviews: the scan blocks neither readers nor writers, unlike
-- an inline-validated ADD CONSTRAINT, which scans while holding ACCESS EXCLUSIVE. Each statement is its own scan.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE reviews VALIDATE CONSTRAINT chk_reviews_source;
ALTER TABLE reviews VALIDATE CONSTRAINT chk_reviews_source_author;
ALTER TABLE reviews VALIDATE CONSTRAINT chk_reviews_guest_display_name_no_digits;
