-- non-destructive
-- Reason: both tables are created in this file; their CHECKs and unique constraints constrain only rows written by this release.
-- Phase 399 — guest reviews: the invite that lets a LINK guest review a visit (D3), and a phone-level SMS opt-out (D4).
--
-- review_invites (D3): one row per visit (appointment_id UNIQUE). Created SCHEDULED by phase 400 when the visit is
-- completed; the send job sets SENT with token_hash, sent_at, expires_at; phase 401 consumes it (CONSUMED,
-- consumed_at). token_hash = SHA-256 of the raw 128-bit token, generated at send time; the raw token is NEVER stored
-- (it lives only in the SMS body). phone_hash = PhoneHasher (phase 397: HMAC-SHA-256 of the E.164 phone, pepper
-- PHONE_HASH_PEPPER) — never the raw phone. ON DELETE CASCADE from appointments: an invite has no meaning without
-- its visit. No external-storage column, so no pre-CASCADE sweep is needed (§O-8 does not apply).
--
-- Lifecycle CHECKs (status is NOT NULL, so none of them passes vacuously):
--   * a SENT / CONSUMED / EXPIRED invite was sent: token_hash, sent_at and expires_at are set;
--   * consumed_at is set exactly on CONSUMED rows;
--   * skip_reason is set exactly on SKIPPED rows, and only to a ReviewInviteSkipReason value (phase 400 D2/D3, 402 D3).
-- SCHEDULED / FAILED are left open on token_hash so phase 400's send/retry ordering is not pre-decided here.
--
-- Index (status, send_after) backs the send job's due-row claim (phase 400 D4). The UNIQUE constraints on
-- appointment_id and token_hash also serve the create-idempotency and the token lookup (phase 401 D2).
--
-- sms_opt_outs (D4): phone_hash PRIMARY KEY (same PhoneHasher key as review_invites and client_blocks — one key
-- across features, no second pepper). Kept when guest PII is erased unless a ticket asks (phase 402 D2). source
-- names where the opt-out came from; REVIEW_INVITE (phase 401 D6) is the only documented source, so the CHECK lists
-- only it — a future source replaces chk_sms_opt_outs_source by name in a new migration.
CREATE TABLE review_invites (
    id              UUID         PRIMARY KEY,
    appointment_id  UUID         NOT NULL REFERENCES appointments(id) ON DELETE CASCADE,
    phone_hash      BYTEA        NOT NULL,
    token_hash      BYTEA        NULL,
    status          VARCHAR(16)  NOT NULL,
    send_after      TIMESTAMPTZ  NOT NULL,
    sent_at         TIMESTAMPTZ  NULL,
    expires_at      TIMESTAMPTZ  NULL,
    consumed_at     TIMESTAMPTZ  NULL,
    attempts        SMALLINT     NOT NULL DEFAULT 0,
    skip_reason     VARCHAR(32)  NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_review_invites_appointment UNIQUE (appointment_id),
    CONSTRAINT ux_review_invites_token_hash UNIQUE (token_hash),
    CONSTRAINT chk_review_invites_status
        CHECK (status IN ('SCHEDULED', 'SENT', 'CONSUMED', 'EXPIRED', 'SKIPPED', 'FAILED')),
    CONSTRAINT chk_review_invites_phone_hash_len CHECK (octet_length(phone_hash) = 32),
    CONSTRAINT chk_review_invites_token_hash_len CHECK (token_hash IS NULL OR octet_length(token_hash) = 32),
    CONSTRAINT chk_review_invites_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_review_invites_sent_fields CHECK (
        status NOT IN ('SENT', 'CONSUMED', 'EXPIRED')
        OR (token_hash IS NOT NULL AND sent_at IS NOT NULL AND expires_at IS NOT NULL)
    ),
    CONSTRAINT chk_review_invites_consumed_at CHECK ((status = 'CONSUMED') = (consumed_at IS NOT NULL)),
    CONSTRAINT chk_review_invites_skip_reason CHECK (
        (status = 'SKIPPED') = (skip_reason IS NOT NULL AND char_length(btrim(skip_reason)) > 0)
    ),
    -- Mirrors enum ReviewInviteSkipReason (phase 400 D2/D3, 402 D3). A new reason replaces this CHECK by name.
    CONSTRAINT chk_review_invites_skip_reason_value CHECK (
        skip_reason IS NULL OR skip_reason IN ('NO_PHONE', 'ANONYMISED', 'SMS_DISABLED', 'INVITES_DISABLED',
                                               'OPTED_OUT', 'NOTHING_TO_REVIEW', 'POLICY_VERSION', 'STALE')
    )
);

CREATE INDEX idx_review_invites_status_send_after ON review_invites (status, send_after);

CREATE TABLE sms_opt_outs (
    phone_hash  BYTEA        PRIMARY KEY,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    source      VARCHAR(16)  NOT NULL,
    CONSTRAINT chk_sms_opt_outs_phone_hash_len CHECK (octet_length(phone_hash) = 32),
    CONSTRAINT chk_sms_opt_outs_source CHECK (source IN ('REVIEW_INVITE'))
);

COMMENT ON TABLE review_invites IS
    'Phase 399 D3. One SMS review invite per LINK visit. token_hash = SHA-256 of the raw token (never stored); '
    'phone_hash = PhoneHasher HMAC (never the raw phone).';
COMMENT ON TABLE sms_opt_outs IS
    'Phase 399 D4. Phones (PhoneHasher HMAC, never raw) that opted out of review-invite SMS. source = where the '
    'opt-out came from; REVIEW_INVITE (the /r/{token} page, phase 401) is the only source today.';
