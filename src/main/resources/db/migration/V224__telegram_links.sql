-- non-destructive
-- Reason: both tables are created in this file; their CHECKs, UNIQUE and FK constrain only rows written by this release.
-- Phase 405 — Telegram links (guest phones only) + the two-step link proof.
--
-- 1. telegram_links — one row per guest phone whose non-urgent messages go to Telegram (D1: no row = SMS).
--    Keyed by phone_hash = PhoneHasher HMAC-SHA-256 (phase 397 pepper), never the raw phone (D2). chat_id is stored
--    plain (D3): useless without the bot token, not linkable to a phone without the pepper; retention/erasure = 409.
--    phone_hash is unique (D5: a re-link from another chat rebinds, latest proven owner wins); chat_id is not.
--    A row exists only after a matching contact share; opt-out deletes it.
--
-- 2. telegram_link_tokens — deep-link start tokens. Only the 32-byte SHA-256 of the raw 43-char base64url token is
--    stored (same rationale as CancelTokenHasher / review_invites.token_hash). phone_hash is the booking's guest
--    phone hash the shared contact must match. Lifecycle ISSUED -> CLAIMED (by chat) -> LINKED | MISMATCH (D7);
--    releasing a claim returns the row to ISSUED with both claim columns NULL. TTL app.telegram.link-token-ttl
--    (30 min); 409's daily job purges expired rows. Rows cascade with their booking.
--
-- Every CHECK column is NOT NULL or paired, so a NULL cannot slip past a CHECK.
CREATE TABLE telegram_links (
    id                uuid        PRIMARY KEY,
    phone_hash        bytea       NOT NULL,
    chat_id           bigint      NOT NULL,
    status            varchar(16) NOT NULL,
    linked_at         timestamptz NOT NULL,
    status_changed_at timestamptz NOT NULL,
    last_sent_at      timestamptz,
    CONSTRAINT ux_telegram_links_phone    UNIQUE (phone_hash),
    CONSTRAINT chk_tg_link_status         CHECK (status IN ('ACTIVE', 'BLOCKED')),
    CONSTRAINT chk_tg_link_phone_hash_len CHECK (octet_length(phone_hash) = 32)
);
CREATE INDEX idx_telegram_links_chat ON telegram_links(chat_id);
-- Phase 409's purge of long-BLOCKED links filters status = 'BLOCKED' and ranges on status_changed_at.
CREATE INDEX idx_telegram_links_blocked ON telegram_links(status_changed_at) WHERE status = 'BLOCKED';

CREATE TABLE telegram_link_tokens (
    token_hash        bytea       PRIMARY KEY,
    booking_id        uuid        NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    phone_hash        bytea       NOT NULL,
    state             varchar(16) NOT NULL,
    expires_at        timestamptz NOT NULL,
    claimed_chat_id   bigint,
    claimed_at        timestamptz,
    finished_at       timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_tg_token_state          CHECK (state IN ('ISSUED', 'CLAIMED', 'LINKED', 'MISMATCH')),
    CONSTRAINT chk_tg_token_claim          CHECK ((state = 'ISSUED') = (claimed_chat_id IS NULL)),
    CONSTRAINT chk_tg_token_claim_pair     CHECK ((claimed_chat_id IS NULL) = (claimed_at IS NULL)),
    CONSTRAINT chk_tg_token_finished       CHECK ((state IN ('LINKED', 'MISMATCH')) = (finished_at IS NOT NULL)),
    CONSTRAINT chk_tg_token_hash_len       CHECK (octet_length(token_hash) = 32),
    CONSTRAINT chk_tg_token_phone_hash_len CHECK (octet_length(phone_hash) = 32)
);
-- booking_id backs the ON DELETE CASCADE: without it every booking delete scans the table.
CREATE INDEX idx_tg_tokens_booking ON telegram_link_tokens(booking_id);
CREATE INDEX idx_tg_tokens_phone   ON telegram_link_tokens(phone_hash);
CREATE INDEX idx_tg_tokens_claim   ON telegram_link_tokens(claimed_chat_id) WHERE state = 'CLAIMED';
CREATE INDEX idx_tg_tokens_expiry  ON telegram_link_tokens(expires_at);
