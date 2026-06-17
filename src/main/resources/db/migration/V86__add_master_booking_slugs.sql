-- Phase 13.1 — Guest Booking Link: public per-master booking slug.
--
-- DEVIATION FROM PHASE DOC: the doc assumed two physical tables (masters +
-- independent_masters) with Long PKs. The actual schema has a SINGLE `masters`
-- table (UUID PK); independent masters are `masters` rows with
-- master_type = 'INDEPENDENT_MASTER'. There is no `independent_masters` table,
-- so only `masters` gets the column.
--
-- Nullable initially; backfilled lazily by BookingSlugService.getOrCreateSlug
-- on first lookup / on master creation. The slug is the public booking-page key
-- shared as beautica.app/book/{slug}; format is ASCII, lowercase, <= 60 chars.
ALTER TABLE masters ADD COLUMN booking_slug VARCHAR(60);

-- Format CHECK (Anti-Bug §O-2): the slug is an opaque public URL key built from
-- transliterated name + '-' + 4 random a-z0-9 chars. Constrain it to the exact
-- charset the generator emits so a future code bug cannot persist a slug with
-- spaces, uppercase, slashes, or path-traversal sequences.
ALTER TABLE masters
    ADD CONSTRAINT ck_masters_booking_slug_format
    CHECK (booking_slug IS NULL OR booking_slug ~ '^[a-z0-9][a-z0-9\-]*[a-z0-9]$');

-- Partial UNIQUE index: enforces global slug uniqueness while permitting the many
-- legacy NULL rows during lazy backfill (a plain UNIQUE column would also allow
-- multiple NULLs in Postgres, but the partial index documents the intent and keeps
-- the index small — Anti-Bug §O-6).
CREATE UNIQUE INDEX idx_masters_booking_slug
    ON masters (booking_slug)
    WHERE booking_slug IS NOT NULL;
