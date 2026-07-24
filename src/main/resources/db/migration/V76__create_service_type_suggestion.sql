-- Phase 16.8: persist service-type suggestions + admin email-link review.
--
-- Today suggestServiceType(...) is fire-and-forget (an admin email only). This
-- migration adds a durable PENDING record carrying a hashed single-use token,
-- mirroring the platform_categories request/approval workflow (V64/V65). The
-- admin approves/rejects via an emailed token-authenticated link.
--
-- DECISION LOCKED: this is its own small table — NOT a row in service_types.
-- An unreviewed suggestion must never appear in the catalog/picker, and approve
-- does NOT auto-insert a service_types row (catalog insertion stays a deliberate,
-- separately-reviewed migration step).
--
-- requested_by_user_id is UUID (users.id is a UUID PK, @GeneratedValue(UUID)),
-- mirroring platform_categories.requested_by_user_id. NO ON DELETE CASCADE —
-- the suggestion record is platform audit data that must survive the requester's
-- account deletion; ON DELETE SET NULL clears the dangling ref while preserving
-- the row (matches the category-request FK convention in V65).
--
-- Idempotent: CREATE TABLE / CREATE INDEX IF NOT EXISTS so a clean-DB replay
-- yields an identical result and a re-apply is a no-op.

CREATE TABLE IF NOT EXISTS service_type_suggestion (
    id                    UUID         PRIMARY KEY,
    requested_by_user_id  UUID,
    category_name         VARCHAR(100) NOT NULL,
    suggested_name        VARCHAR(255) NOT NULL,
    description           VARCHAR(1000),
    status                VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    token_hash            VARCHAR(64),
    token_expires_at      TIMESTAMPTZ,
    decided_at            TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    -- Status is a closed set; CHECK mirrors the ServiceTypeSuggestionStatus enum.
    CONSTRAINT chk_service_type_suggestion_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),

    -- Cross-field CHECK: token columns only carry a value while a decision is
    -- still outstanding. APPROVED/REJECTED rows have their token nulled
    -- (single-use), mirroring platform_categories.chk_..._token_pending.
    CONSTRAINT chk_service_type_suggestion_token_pending
        CHECK (
            (token_hash IS NULL AND token_expires_at IS NULL)
            OR (token_hash IS NOT NULL AND token_expires_at IS NOT NULL AND status = 'PENDING')
        ),

    CONSTRAINT fk_service_type_suggestion_requested_by
        FOREIGN KEY (requested_by_user_id) REFERENCES users (id) ON DELETE SET NULL
);

-- Partial index backing the token-lookup hot path on the approve/reject POST:
-- only PENDING rows carry a non-null token_hash, so a partial index is the
-- minimal-write mirror of the token_hash = ? lookup (matches platform_categories).
CREATE INDEX IF NOT EXISTS idx_service_type_suggestion_token_hash
    ON service_type_suggestion (token_hash)
    WHERE token_hash IS NOT NULL;

-- Partial index backing the dedup existence lookup: a pending suggestion for the
-- same (category_name, lower(suggested_name)) blocks a duplicate. lower(...) is
-- indexed to match the service-layer normalized comparison; scoped to PENDING so
-- a previously decided suggestion may be re-suggested.
CREATE INDEX IF NOT EXISTS idx_service_type_suggestion_dedup
    ON service_type_suggestion (category_name, lower(suggested_name))
    WHERE status = 'PENDING';
