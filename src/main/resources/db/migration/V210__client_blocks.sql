-- non-destructive
-- Reason: new table created in this file; its CHECKs and unique indexes constrain only rows written by this release.
-- Phase 397 — a provider blocks a client (registered account or LINK guest). Effects are phase 398.
--
-- Blocker scope (D1): exactly one of blocker_master_id (independent master, own scope) or blocker_salon_id (the
-- whole salon, shared by every owner/admin). Blocked identity (D2): blocked_user_id for a registered client and/or
-- phone_hash = HMAC-SHA256(E.164 phone, PHONE_HASH_PEPPER) (D3). No raw phone column — only the last four digits
-- for the masked display (D4) and a display-name snapshot.
--
-- Cascades (D6): a deleted blocker master/salon or blocked user takes its rows with it (the phone hash goes with
-- the row). created_by_user_id is audit only and is nulled when that account is deleted. No external-storage
-- column, so no pre-CASCADE sweep is needed (§O-8 does not apply).
CREATE TABLE client_blocks (
    id                  UUID         PRIMARY KEY,
    blocker_master_id   UUID         NULL REFERENCES masters(id) ON DELETE CASCADE,
    blocker_salon_id    UUID         NULL REFERENCES salons(id) ON DELETE CASCADE,
    blocked_user_id     UUID         NULL REFERENCES users(id) ON DELETE CASCADE,
    phone_hash          BYTEA        NULL,
    phone_last4         CHAR(4)      NULL,
    display_name        VARCHAR(64)  NOT NULL,
    created_by_user_id  UUID         NULL REFERENCES users(id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- num_nonnulls is never NULL itself, so neither CHECK passes vacuously on NULL input.
    CONSTRAINT chk_client_blocks_one_blocker CHECK (num_nonnulls(blocker_master_id, blocker_salon_id) = 1),
    CONSTRAINT chk_client_blocks_blocked_identity CHECK (num_nonnulls(blocked_user_id, phone_hash) >= 1),
    CONSTRAINT chk_client_blocks_phone_hash_len CHECK (phone_hash IS NULL OR octet_length(phone_hash) = 32),
    CONSTRAINT chk_client_blocks_phone_last4 CHECK (phone_last4 IS NULL OR phone_last4 ~ '^[0-9]{4}$'),
    CONSTRAINT chk_client_blocks_display_name CHECK (char_length(btrim(display_name)) > 0)
);

-- D5 idempotency: one row per (blocker, identity key). Also the arbiters of the insert's ON CONFLICT DO NOTHING.
CREATE UNIQUE INDEX ux_client_blocks_master_user
    ON client_blocks (blocker_master_id, blocked_user_id)
    WHERE blocker_master_id IS NOT NULL AND blocked_user_id IS NOT NULL;
CREATE UNIQUE INDEX ux_client_blocks_salon_user
    ON client_blocks (blocker_salon_id, blocked_user_id)
    WHERE blocker_salon_id IS NOT NULL AND blocked_user_id IS NOT NULL;
CREATE UNIQUE INDEX ux_client_blocks_master_phone
    ON client_blocks (blocker_master_id, phone_hash)
    WHERE blocker_master_id IS NOT NULL AND phone_hash IS NOT NULL;
CREATE UNIQUE INDEX ux_client_blocks_salon_phone
    ON client_blocks (blocker_salon_id, phone_hash)
    WHERE blocker_salon_id IS NOT NULL AND phone_hash IS NOT NULL;

-- Phase-398 enforcement looks a phone up across scopes.
CREATE INDEX idx_client_blocks_phone_hash ON client_blocks (phone_hash) WHERE phone_hash IS NOT NULL;

-- List + FK-cascade support (the partial uniques above skip rows whose identity column is NULL).
CREATE INDEX idx_client_blocks_blocker_master ON client_blocks (blocker_master_id, created_at)
    WHERE blocker_master_id IS NOT NULL;
CREATE INDEX idx_client_blocks_blocker_salon ON client_blocks (blocker_salon_id, created_at)
    WHERE blocker_salon_id IS NOT NULL;
CREATE INDEX idx_client_blocks_blocked_user ON client_blocks (blocked_user_id) WHERE blocked_user_id IS NOT NULL;
CREATE INDEX idx_client_blocks_created_by ON client_blocks (created_by_user_id) WHERE created_by_user_id IS NOT NULL;
