-- Phase 378 audit (security MEDIUM) — retired_booking_slugs keeps a HASH of the slug, never the slug.
--
-- contract-of: V204
--
-- Why: a slug is the transliterated provider / salon name plus 4 random chars, and the tombstone row
-- outlives account erasure (V204 FKs are ON DELETE SET NULL). Keeping the plaintext slug would keep a
-- name-derived identifier after the person is gone. The tombstone only has to answer "was (kind, slug)
-- ever retired?", which a hash answers equally well: the application computes
-- lower-hex SHA-256 of  kind || ':' || slug  (UTF-8) — the one helper is RetiredSlugHasher — and every
-- probe and insert uses that value.
--
-- Expand/contract justification (phase 370 D6 gate — flagged for DROP CONSTRAINT <pkey> and DROP COLUMN
-- slug): V204 created retired_booking_slugs in the SAME unreleased phase 378. No deployed release has
-- ever read or written retired_booking_slugs.slug, so the image running before this one (rollback
-- target) never touches the dropped column or key.
--
-- Not editing V204: it is already applied on developer databases (immutable once applied, §O-9).
--
-- Locking: one transaction (Flyway); ALTERs take ACCESS EXCLUSIVE on retired_booking_slugs only — a
-- small table that only regenerate / revoke / slug generation touch.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE retired_booking_slugs ADD COLUMN slug_hash VARCHAR(64);

-- Backfill: the exact preimage the application hashes (kind is 'MASTER' / 'SALON', the enum name).
UPDATE retired_booking_slugs
   SET slug_hash = encode(sha256(convert_to(kind || ':' || slug, 'UTF8')), 'hex');

ALTER TABLE retired_booking_slugs ALTER COLUMN slug_hash SET NOT NULL;

-- Anti-Bug §O-2: exactly the lower-hex SHA-256 the application emits.
ALTER TABLE retired_booking_slugs
    ADD CONSTRAINT ck_retired_booking_slugs_slug_hash_format CHECK (slug_hash ~ '^[0-9a-f]{64}$');

-- The key moves from (kind, slug) to (kind, slug_hash); same name, so the entity mapping is unchanged.
ALTER TABLE retired_booking_slugs DROP CONSTRAINT retired_booking_slugs_pkey;
ALTER TABLE retired_booking_slugs ADD CONSTRAINT retired_booking_slugs_pkey PRIMARY KEY (kind, slug_hash);

-- The plaintext goes (its format CHECK first, explicitly, rather than implicitly with the column).
ALTER TABLE retired_booking_slugs DROP CONSTRAINT ck_retired_booking_slugs_slug_format;
ALTER TABLE retired_booking_slugs DROP COLUMN slug;
