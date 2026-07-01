-- Phase 13.6 (Public Salon Profile): adds the persisted rating aggregate + cover image
-- columns to salons, mirroring masters.avg_rating / masters.review_count (V4).
--
-- avg_rating / review_count follow the same "recalculate on write, read persisted on
-- read" contract as masters: ReviewEventListener.onReviewCreated recalculates them via
-- ReviewRepository.recalculateSalonRating after a review commits. review_count defaults
-- to 0 (NOT NULL) so raw-SQL inserts/fixtures that omit the column never violate a NOT
-- NULL constraint (anti-bug §O.1). avg_rating stays nullable (no DEFAULT) — a salon with
-- zero reviews has never been recalculated and legitimately has no average yet.
--
-- No CHECK constraint on cover_image_url: there is no upload endpoint yet (planned with
-- the Phase 9 media feature), so format validation is deferred to that migration.
ALTER TABLE salons
    ADD COLUMN cover_image_url VARCHAR(2048),
    ADD COLUMN avg_rating NUMERIC(3,2),
    ADD COLUMN review_count INTEGER NOT NULL DEFAULT 0;
