-- non-destructive
-- Reason: new table created in this file; its CHECKs and indexes constrain only rows written by this release.
--
-- V268 — B564 (CLI-82..85, CLI-D17, MOD-40): a provider removes a client from their «Клієнти» list, or undoes it.
-- One row = one hidden list row. It hides the row only while no booking for that client was CREATED in the scope
-- after removed_at (CLI-83, the predicate lives in ClientListSql); bookings, history, ratings and client_blocks are
-- never touched (CLI-84, MOD-40).
--
-- Scope (D1) — exactly one of:
--   salon_id  : the salon list, shared by the owner and every admin (also their ?masterId= view);
--   master_id : a salon master's own list, or an independent master's list.
-- Identity — exactly one of:
--   client_user_id : a registered client (D4);
--   guest_phone    : a guest, the plain E.164 phone as stored in bookings.guest_phone, i.e. the value the list
--                    groups guests on (D3). Not an HMAC: the list SQL must join on it. It is personal data, so it is
--                    purged with the guest's PII (GST-51): GuestPiiAnonymisationService deletes the phone's rows in
--                    the anonymising transaction once no open guest booking of the phone is left.
-- Both CHECKs use `(a IS NULL) <> (b IS NULL)`: both operands are IS [NOT] NULL tests, never NULL, so neither CHECK
-- passes vacuously on NULL input (both-null and both-set are false).
--
-- Cascades: a deleted salon / master / client account takes its rows with it; removed_by_user_id is audit only and
-- is nulled when that account is deleted. No external-storage column, so no pre-CASCADE sweep (§O-8 n/a).
-- Volume: small (a provider's manual removals); the list reads it per page through the partial uniques below.
--
-- Lock safety (/sql § 4): the three FOREIGN KEYs take SHARE ROW EXCLUSIVE on the hot parents salons / masters / users
-- for the rest of this transaction. Fail fast instead of queueing behind live traffic (same values as V211); the new
-- table is empty, so its CHECKs and indexes cost nothing to build.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

CREATE TABLE client_list_removals (
    id                 UUID         PRIMARY KEY,
    salon_id           UUID         NULL REFERENCES salons(id) ON DELETE CASCADE,
    master_id          UUID         NULL REFERENCES masters(id) ON DELETE CASCADE,
    client_user_id     UUID         NULL REFERENCES users(id) ON DELETE CASCADE,
    guest_phone        VARCHAR(20)  NULL,
    removed_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    removed_by_user_id UUID         NULL REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_clr_one_scope    CHECK ((salon_id IS NULL) <> (master_id IS NULL)),
    CONSTRAINT chk_clr_one_identity CHECK ((client_user_id IS NULL) <> (guest_phone IS NULL)),
    -- Same E.164 guard as bookings.guest_phone (V89 chk_bookings_guest_phone_format).
    CONSTRAINT chk_clr_guest_phone_format CHECK (guest_phone IS NULL OR guest_phone ~ '^\+[0-9]{6,18}$')
);

-- One row per (scope, identity): the arbiters of the upserts' ON CONFLICT and the list's LEFT JOIN lookup.
CREATE UNIQUE INDEX ux_clr_salon_user
    ON client_list_removals (salon_id, client_user_id)
    WHERE salon_id IS NOT NULL AND client_user_id IS NOT NULL;
CREATE UNIQUE INDEX ux_clr_salon_phone
    ON client_list_removals (salon_id, guest_phone)
    WHERE salon_id IS NOT NULL AND guest_phone IS NOT NULL;
CREATE UNIQUE INDEX ux_clr_master_user
    ON client_list_removals (master_id, client_user_id)
    WHERE master_id IS NOT NULL AND client_user_id IS NOT NULL;
CREATE UNIQUE INDEX ux_clr_master_phone
    ON client_list_removals (master_id, guest_phone)
    WHERE master_id IS NOT NULL AND guest_phone IS NOT NULL;

-- FK-cascade support: the partial uniques above cannot serve a bare `WHERE salon_id = ?` (their predicates also need
-- the identity column), and the GST-51 purge looks rows up by phone across scopes.
CREATE INDEX idx_clr_salon ON client_list_removals (salon_id) WHERE salon_id IS NOT NULL;
CREATE INDEX idx_clr_master ON client_list_removals (master_id) WHERE master_id IS NOT NULL;
CREATE INDEX idx_clr_client_user ON client_list_removals (client_user_id) WHERE client_user_id IS NOT NULL;
CREATE INDEX idx_clr_guest_phone ON client_list_removals (guest_phone) WHERE guest_phone IS NOT NULL;
CREATE INDEX idx_clr_removed_by ON client_list_removals (removed_by_user_id) WHERE removed_by_user_id IS NOT NULL;
