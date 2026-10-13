-- Phase 363 D1 (follow-up to V193) — validate fk_media_files_uploader_id.
--
-- V193 added the FK NOT VALID. Flyway runs each versioned migration in its own transaction, so
-- this VALIDATE runs without V193's ACCESS EXCLUSIVE (DROP NOT NULL) on media_files or its SHARE
-- ROW EXCLUSIVE (ADD CONSTRAINT) on users. Here it takes only SHARE UPDATE EXCLUSIVE on
-- media_files and ROW SHARE on users, so reads and writes on both tables proceed during the scan.

-- Fail fast instead of queueing behind long-running DDL on either table.
SET LOCAL lock_timeout = '5s';

ALTER TABLE media_files VALIDATE CONSTRAINT fk_media_files_uploader_id;
