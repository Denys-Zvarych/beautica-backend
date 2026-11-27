-- V270 — B546 audit-fix (INFO): make idx_client_reviews_subject_client a PARTIAL index WHERE subject_client_id IS NOT NULL.
--
-- WHY.
-- V269 made subject_client_id nullable: a phone-keyed guest / walk-in rating (subject_guest_phone set) carries NULL here.
-- Every reader of this index filters on equality subject_client_id = :id (ClientReviewRepository#recalculateClientRating,
-- the "reviews I've received" reads, ClientRatingSql FILTER, deleteBySubjectClientId, and the RI probe Postgres runs on
-- DELETE FROM users through the NO ACTION FK). Equality is a strict operator, so the planner proves
-- `subject_client_id = $1` implies `subject_client_id IS NOT NULL` (generic plans included) and keeps using the
-- partial index; the NULL entries of guest rows are pure write cost and are dropped.
--
-- SAME NAME, ON PURPOSE.
-- ClientReview's @Table(indexes) declares idx_client_reviews_subject_client, so the partial index keeps that name
-- (@Index cannot declare a partial predicate; the entity documents it, as V269 did for the guest-phone index). That needs
-- DROP then CREATE in this one migration. V128 / V269 are NOT edited: they are applied everywhere (checksums).
--
-- WHY PLAIN DROP / CREATE AND NOT CONCURRENTLY.
-- House pattern (V265, V138): Flyway runs every migration in one transaction (no executeInTransaction=false sidecar),
-- so CREATE INDEX CONCURRENTLY cannot run here. Inside one transaction the swap is atomic: no reader ever sees the table
-- without the index, and a failure rolls both statements back. DROP INDEX takes ACCESS EXCLUSIVE on client_reviews and
-- the build takes SHARE; client_reviews is a small table, so fail fast instead of queueing behind traffic (same values
-- as V269 on the same table). Non-unique index, so a rollback to the previous app version is unaffected.
--
-- IF EXISTS / IF NOT EXISTS keep a replay idempotent. No column, CHECK or row touched (§O-8 n/a).
-- Fix-forward version: max existing = V269.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

DROP INDEX IF EXISTS idx_client_reviews_subject_client;

CREATE INDEX IF NOT EXISTS idx_client_reviews_subject_client
    ON client_reviews (subject_client_id)
    WHERE subject_client_id IS NOT NULL;
