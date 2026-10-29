-- Phase 414 — one-click moderation actions from the support report e-mail.
-- One row per e-mailed button: a 7-day, single-use capability for (target, action). Only the lower-hex SHA-256 of the
-- raw token is stored (the raw token lives in the e-mail body only), same scheme as platform_categories.token_hash.
-- GET /api/v1/moderation/actions/{token} stores the hash of a 30-minute confirm nonce; POST .../confirm claims the row
-- (used_at) and runs the phase-396 resolve. Any resolution of the target revokes every live row (revoked_at).
-- No FK to the target: targets are polymorphic, exactly as content_reports.
-- Hash columns are VARCHAR(64) (project convention for SHA-256 hex, see V206); the CHECKs pin the exact format.
CREATE TABLE moderation_action_tokens (
    id                        uuid         PRIMARY KEY,
    target_type               varchar(32)  NOT NULL,
    target_id                 uuid         NOT NULL,
    action                    varchar(16)  NOT NULL,
    token_hash                varchar(64)  NOT NULL,
    confirm_nonce_hash        varchar(64),
    confirm_nonce_expires_at  timestamptz,
    expires_at                timestamptz  NOT NULL,
    used_at                   timestamptz,
    revoked_at                timestamptz,
    created_at                timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_mat_action      CHECK (action IN ('REMOVE', 'DISMISS', 'RESTORE')),
    CONSTRAINT chk_mat_target_type CHECK (target_type IN ('REVIEW', 'CLIENT_REVIEW', 'PORTFOLIO_PHOTO',
                                                          'MASTER_PROFILE', 'SALON_PROFILE', 'CLIENT_PROFILE')),
    CONSTRAINT chk_mat_restore     CHECK (action <> 'RESTORE' OR target_type IN ('REVIEW', 'CLIENT_REVIEW')),
    CONSTRAINT chk_mat_hash_fmt    CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_mat_nonce_fmt   CHECK (confirm_nonce_hash IS NULL OR confirm_nonce_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_mat_nonce_pair  CHECK ((confirm_nonce_hash IS NULL) = (confirm_nonce_expires_at IS NULL))
);

CREATE UNIQUE INDEX ux_mat_token_hash ON moderation_action_tokens (token_hash);

-- revokeAllForTarget (every resolve) touches the not-yet-revoked rows of one target, claimed ones included, so a
-- released claim can never outlive a concurrent resolve; the predicate must match its WHERE exactly.
CREATE INDEX ix_mat_live_target ON moderation_action_tokens (target_type, target_id)
    WHERE revoked_at IS NULL;

-- Retention purge (expires_at < now - 30 days).
CREATE INDEX ix_mat_expires_at ON moderation_action_tokens (expires_at);
