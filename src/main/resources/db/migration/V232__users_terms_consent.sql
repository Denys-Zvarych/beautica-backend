-- non-destructive
-- Reason: nullable columns added in this file; the CHECK only constrains columns that are NULL on every existing row.
-- Phase 442 — terms + privacy acceptance recorded at account creation (Google Play UGC policy).
--
-- Additive only: three nullable columns on users plus one triple CHECK ("all NULL or all NOT NULL"),
-- written as an equality of IS NULL tests (a CHECK passes on NULL, so `col <> ''` would prove nothing).
-- No backfill (D6): NULL means "registered before consent was recorded". Set once at creation by
-- AuthService.register* / InviteService.acceptInvite from the server clock; never updated (D7).
--
-- Locking (as V202): one transaction; ACCESS EXCLUSIVE on users until COMMIT. lock_timeout bounds the
-- wait to acquire it; statement_timeout bounds the CHECK validation scan.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE users
    ADD COLUMN terms_accepted_at TIMESTAMPTZ,
    ADD COLUMN terms_version VARCHAR(32),
    ADD COLUMN privacy_policy_version VARCHAR(32);

ALTER TABLE users
    ADD CONSTRAINT ck_users_terms_consent_triple
    CHECK ((terms_accepted_at IS NULL) = (terms_version IS NULL)
       AND (terms_accepted_at IS NULL) = (privacy_policy_version IS NULL));
