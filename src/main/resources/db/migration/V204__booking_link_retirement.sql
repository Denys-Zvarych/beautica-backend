-- non-destructive
-- Reason: both CHECKs on masters/salons reference booking_link_revoked_at, a column added NULL in this same file — no existing row or previous-app write can violate them; the rest is a new table.
-- Phase 378 — regenerate + revoke booking links (retired-slug tombstone).
--
-- Planned as V201 (a reservation only); V201 was never used and V202/V203 are already applied on
-- this branch, so Flyway (outOfOrder=false) takes the next free number.
--
-- 1. booking_link_revoked_at on masters and salons (D4): set by a revoke, cleared by a regenerate.
--    While it is set the share path (GET /api/v1/booking-links/mine) never issues a slug, so a
--    revoked link is not silently resurrected. NOT mapped on the JPA entities (D12) — read through
--    native projections only. The CHECK pins "revoked => no live slug".
-- 2. retired_booking_slugs (D2/D3): every slug ever retired, keyed (kind, slug) because master and
--    salon slugs live in different URL namespaces (/book/ vs /book/s/). Both generators treat a
--    tombstoned (kind, slug) as taken, so a retired slug is never issued to anyone again. The row
--    is also the audit trail (who, why, when — D14). ON DELETE SET NULL keeps the tombstone after
--    the master / salon / user row is gone. No backfill: nothing has been retired before this file.
--
-- Locking (as V200): Flyway runs this file in ONE transaction; the ALTERs take ACCESS EXCLUSIVE on
-- masters and salons until COMMIT. lock_timeout bounds the wait to ACQUIRE them; statement_timeout
-- bounds the CHECK scans (every row passes: the column is new and NULL).
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE masters ADD COLUMN booking_link_revoked_at TIMESTAMPTZ;
ALTER TABLE masters
    ADD CONSTRAINT ck_masters_booking_link_revoked_no_slug
    CHECK (booking_link_revoked_at IS NULL OR booking_slug IS NULL);

ALTER TABLE salons ADD COLUMN booking_link_revoked_at TIMESTAMPTZ;
ALTER TABLE salons
    ADD CONSTRAINT ck_salons_booking_link_revoked_no_slug
    CHECK (booking_link_revoked_at IS NULL OR booking_slug IS NULL);

CREATE TABLE retired_booking_slugs (
    kind               VARCHAR(10) NOT NULL,
    slug               VARCHAR(60) NOT NULL,
    master_id          UUID REFERENCES masters (id) ON DELETE SET NULL,
    salon_id           UUID REFERENCES salons (id) ON DELETE SET NULL,
    retired_by_user_id UUID REFERENCES users (id) ON DELETE SET NULL,
    reason             VARCHAR(16) NOT NULL,
    retired_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (kind, slug),
    CONSTRAINT ck_retired_booking_slugs_kind CHECK (kind IN ('MASTER', 'SALON')),
    CONSTRAINT ck_retired_booking_slugs_reason CHECK (reason IN ('REGENERATED', 'REVOKED', 'DELETED')),
    -- Anti-Bug §O-2: the exact charset the generator emits (V86 / V200 slug CHECK).
    CONSTRAINT ck_retired_booking_slugs_slug_format CHECK (slug ~ '^[a-z0-9][a-z0-9\-]*[a-z0-9]$'),
    -- Anti-Bug §O-3: a MASTER tombstone never points at a salon and vice versa (both may be NULL
    -- once the owning row is deleted).
    CONSTRAINT ck_retired_booking_slugs_owner_kind CHECK (
        (kind = 'MASTER' AND salon_id IS NULL) OR (kind = 'SALON' AND master_id IS NULL))
);

-- FK-side indexes so deleting a master / salon / user (ON DELETE SET NULL) never seq-scans the
-- tombstone table. Partial: the column is NULL on every row of the other kind.
CREATE INDEX idx_retired_booking_slugs_master ON retired_booking_slugs (master_id) WHERE master_id IS NOT NULL;
CREATE INDEX idx_retired_booking_slugs_salon ON retired_booking_slugs (salon_id) WHERE salon_id IS NOT NULL;
CREATE INDEX idx_retired_booking_slugs_retired_by
    ON retired_booking_slugs (retired_by_user_id) WHERE retired_by_user_id IS NOT NULL;
