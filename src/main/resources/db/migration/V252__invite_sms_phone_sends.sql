-- non-destructive
-- Reason: creates one new table and its index; no existing table, row or constraint is touched.
-- B520 audit-fix cycle 2 (AUTH-D14) — durable log behind the per-phone invite-SMS cap.
--
-- The cap (3 invite SMS per phone per rolling 24 h, any salon / inviter) used to count invite_tokens rows. Those
-- rows are hard-deleted by an owner self-delete (SalonService#detachDeactivatedSalonsForOwnerDeletion, SUP-67 AC5)
-- and by staff disposal, so a register → invite × 3 → delete loop reset the count and could pump one phone without
-- limit. This table has no FK to anything, so no cascade or cleanup path of invite_tokens / salons / users reaches
-- it; its only delete is the daily retention purge of rows past the 24 h window.
--
-- invite_sms_phone_sends — one row per invite SMS enqueued (same transaction as the invite_tokens row and its
-- outbox entry, so a lost race rolls all three back).
--   phone_hash  HMAC-SHA256(E.164 phone, PHONE_HASH_PEPPER) via PhoneHasher — never the raw number (the UA mobile
--               space is small enough to brute-force a bare SHA-256 from a dump). Always 32 bytes.
--   sent_at     when the invite SMS was enqueued; the cap counts rows with sent_at > now - 24 h.
-- Locking: CREATE TABLE + CREATE INDEX on a brand-new empty table; the guards fail fast instead of queueing.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '1min';

CREATE TABLE invite_sms_phone_sends (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    phone_hash BYTEA       NOT NULL,
    sent_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_invite_sms_phone_sends_hash_len CHECK (octet_length(phone_hash) = 32)
);

-- Per-phone cap: WHERE phone_hash = ? AND sent_at > ? (equality first, then the range).
CREATE INDEX idx_invite_sms_phone_sends_phone_sent
    ON invite_sms_phone_sends (phone_hash, sent_at);
