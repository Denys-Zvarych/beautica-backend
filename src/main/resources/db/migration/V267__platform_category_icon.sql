-- non-destructive
-- Reason: adds three NULLable columns and three CHECK constraints to platform_categories; no row is rewritten (a
-- NULLable column with no default is catalog-only) and every existing row passes every CHECK (all three columns NULL).
--
-- V267 — B539 (SVC-58 / SVC-59): a category's icon lives in R2; the row stores only the object KEY and the sha256 of
-- the canonical SVG bytes. The public URL is built at read time from CLOUDFLARE_R2_PUBLIC_URL (track D8), so a media
-- domain change needs no SQL (unlike media_files.r2_url / users.avatar_url).
--
-- No "APPROVED needs an icon" constraint here: B541 adds it after the B540 backfill.
--
-- CHECKS (Postgres CHECK passes on NULL, so each format check is also guarded by the all-or-none check):
--   chk_platform_categories_icon_all_or_none — key, hash and timestamp are set together or not at all.
--   chk_platform_categories_icon_key_format  — exactly the content-addressed key CategoryIconService writes:
--       catalog/category-icons/{wire name}/{first 16 hex of sha256}.svg (no traversal, no leading slash possible).
--   chk_platform_categories_icon_sha256_format — 64 lowercase hex characters.
--
-- LOCKING — ADD COLUMN / ADD CONSTRAINT take ACCESS EXCLUSIVE briefly; the table is reference data (a few dozen rows),
-- so the CHECK validation scan is instant. lock_timeout bounds the wait (V138 rationale). IF NOT EXISTS keeps the
-- column adds replay-safe.

SET LOCAL lock_timeout = '5s';

ALTER TABLE platform_categories ADD COLUMN IF NOT EXISTS icon_key        VARCHAR(200) NULL;
ALTER TABLE platform_categories ADD COLUMN IF NOT EXISTS icon_sha256     CHAR(64)     NULL;
ALTER TABLE platform_categories ADD COLUMN IF NOT EXISTS icon_updated_at TIMESTAMPTZ  NULL;

-- One ALTER for the three constraints: one ACCESS EXCLUSIVE acquisition and one validation pass instead of three.
ALTER TABLE platform_categories
    ADD CONSTRAINT chk_platform_categories_icon_all_or_none
        CHECK ((icon_key IS NULL AND icon_sha256 IS NULL AND icon_updated_at IS NULL)
            OR (icon_key IS NOT NULL AND icon_sha256 IS NOT NULL AND icon_updated_at IS NOT NULL)),
    ADD CONSTRAINT chk_platform_categories_icon_key_format
        CHECK (icon_key IS NULL
            OR icon_key ~ '^catalog/category-icons/[A-Z][A-Z0-9_]{0,99}/[0-9a-f]{16}\.svg$'),
    ADD CONSTRAINT chk_platform_categories_icon_sha256_format
        CHECK (icon_sha256 IS NULL OR icon_sha256 ~ '^[0-9a-f]{64}$');
