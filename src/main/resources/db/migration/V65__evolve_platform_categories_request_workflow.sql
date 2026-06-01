-- Phase: self-service service-category request + email-approval workflow.
--
-- Evolves the existing platform_categories table (V64) IN PLACE — no parallel
-- table. The same row is the authoritative catalog entry AND, while pending, the
-- approval-request record. Ops-created rows (internal X-Internal-Key CRUD) and the
-- 7 seeds are APPROVED; self-service requests start PENDING and become APPROVED
-- only after the platform admin clicks Approve in the emailed confirmation page.
--
-- requested_by_user_id is UUID (NOT bigint): users.id is a UUID PK
-- (@GeneratedValue(strategy = UUID)). The original plan said BIGINT, which would
-- have failed the FK type check — corrected to UUID to match the real schema.

ALTER TABLE platform_categories
    ADD COLUMN display_name          VARCHAR(100),
    ADD COLUMN status                VARCHAR(20)  NOT NULL DEFAULT 'APPROVED',
    ADD COLUMN requested_by_user_id  UUID,
    ADD COLUMN requested_at          TIMESTAMPTZ,
    ADD COLUMN decided_at            TIMESTAMPTZ,
    ADD COLUMN token_hash            VARCHAR(64),
    ADD COLUMN token_expires_at      TIMESTAMPTZ;

-- Backfill Ukrainian display names for the 7 seeded categories, then enforce
-- NOT NULL. Existing rows are already APPROVED via the column DEFAULT above.
UPDATE platform_categories SET display_name = 'Манікюр'  WHERE name = 'MANICURE';
UPDATE platform_categories SET display_name = 'Педикюр'  WHERE name = 'PEDICURE';
UPDATE platform_categories SET display_name = 'Вії'      WHERE name = 'EYELASH';
UPDATE platform_categories SET display_name = 'Стрижка'  WHERE name = 'HAIRCUT';
UPDATE platform_categories SET display_name = 'Макіяж'   WHERE name = 'MAKEUP';
UPDATE platform_categories SET display_name = 'Брови'    WHERE name = 'BROWS';
UPDATE platform_categories SET display_name = 'Інше'     WHERE name = 'OTHER';

-- Any category that pre-dates this migration but is not one of the 7 seeds
-- (ops-created via internal CRUD before display_name existed) gets its name as a
-- safe fallback so the NOT NULL can be applied without failure.
UPDATE platform_categories SET display_name = name WHERE display_name IS NULL;

ALTER TABLE platform_categories
    ALTER COLUMN display_name SET NOT NULL;

-- Status is a closed set; CHECK mirrors the PlatformCategoryStatus enum.
ALTER TABLE platform_categories
    ADD CONSTRAINT chk_platform_categories_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'));

-- Cross-field CHECK: token columns only carry a value while a decision is still
-- outstanding. APPROVED/REJECTED rows have their token nulled (single-use).
ALTER TABLE platform_categories
    ADD CONSTRAINT chk_platform_categories_token_pending
        CHECK (
            (token_hash IS NULL AND token_expires_at IS NULL)
            OR (token_hash IS NOT NULL AND token_expires_at IS NOT NULL AND status = 'PENDING')
        );

-- requested_by_user_id references the requester; NO ON DELETE CASCADE — a
-- category must survive the requester's account deletion (it is platform data).
-- ON DELETE SET NULL preserves the catalog row while clearing the dangling ref.
ALTER TABLE platform_categories
    ADD CONSTRAINT fk_platform_categories_requested_by
        FOREIGN KEY (requested_by_user_id) REFERENCES users (id) ON DELETE SET NULL;

-- The existing uq_platform_categories_name UNIQUE(name) (V64) already blocks two
-- rows sharing a name in ANY status, which is the strongest guarantee — it
-- subsumes the "no two PENDING rows share a name" requirement. Kept as-is.

-- Partial index backing the token-lookup hot path on the approve/reject POST:
-- only PENDING rows carry a non-null token_hash, so a partial index is the
-- minimal-write mirror of the WHERE status = 'PENDING' AND token_hash = ? query.
CREATE INDEX idx_platform_categories_token_hash
    ON platform_categories (token_hash)
    WHERE token_hash IS NOT NULL;

-- Partial index backing GET /service-categories/approved and the usability gate
-- (status = 'APPROVED' AND active = TRUE).
CREATE INDEX idx_platform_categories_approved_active
    ON platform_categories (name)
    WHERE status = 'APPROVED' AND active = TRUE;
