-- non-destructive
-- Reason: creates one new, empty table, its index, and its append-only trigger + function; no existing table, row or
-- constraint is touched.
--
-- V264 — Phase B501 (admin catalog portal): the catalog-admin audit table.
--
-- WHY
-- Every catalog-admin write (B502–B505: request decision, name edit, rename, hide, unhide, delete) stores one
-- append-only row in the caller's transaction (CatalogAdminAuditService, propagation MANDATORY), so a row exists if
-- and only if the change commits. before/after hold catalog fields only — never user PII (a requester's user id is
-- allowed, their email is not). actor is the Cloudflare Access email the website Worker (W039) forwards in
-- X-Admin-Actor. Append-only, enforced twice: the application has no UPDATE or DELETE path for this table, AND the
-- row trigger trg_catalog_admin_audit_append_only rejects every UPDATE and DELETE at the database, so a future code
-- path, a hand-run SQL fix or a compromised app credential cannot rewrite history.
--
-- TRIGGER — BEFORE UPDATE OR DELETE, FOR EACH ROW: raises SQLSTATE 55000 (object_not_in_prerequisite_state) with a
-- fixed message (no row data echoed). A row trigger does not fire on TRUNCATE; the test-suite cleanup relies on that
-- (AbstractIntegrationTest uses TRUNCATE for this table). No migration or runtime path truncates it.
--
-- CONSTRAINTS
-- * chk_catalog_admin_audit_actor: a non-blank, <= 254-char e-mail shape (the NOT NULL alone admits '').
-- * chk_catalog_admin_audit_action: upper snake case, so free text never lands in the action column.
-- * chk_catalog_admin_audit_target_type: the four catalog targets the portal writes.
-- * chk_catalog_admin_audit_target_id: non-blank (NOT NULL alone admits '').
-- * chk_catalog_admin_audit_before/after: a JSON object or NULL (a create has no before, a delete no after).
--   Postgres CHECK passes on NULL, which is exactly the intent for these two.
--
-- INDEX — the per-target history read (target_type, target_id, newest first). No FK: target_id is polymorphic
-- (category name, service-type uuid, request uuid, suggestion uuid) and the row must survive a deleted target.
--
-- Immutable-migration rule (§O-9): next free version (max = V263). New, empty table: no lock concern.

CREATE TABLE catalog_admin_audit (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    actor       VARCHAR(254) NOT NULL,
    action      VARCHAR(40)  NOT NULL,
    target_type VARCHAR(30)  NOT NULL,
    target_id   VARCHAR(100) NOT NULL,
    before      JSONB,
    after       JSONB,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_catalog_admin_audit_actor
        CHECK (btrim(actor) <> '' AND actor ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'),
    CONSTRAINT chk_catalog_admin_audit_action
        CHECK (action ~ '^[A-Z][A-Z_]*[A-Z]$'),
    CONSTRAINT chk_catalog_admin_audit_target_type
        CHECK (target_type IN ('CATEGORY', 'SERVICE_TYPE', 'CATEGORY_REQUEST', 'TYPE_SUGGESTION')),
    CONSTRAINT chk_catalog_admin_audit_target_id
        CHECK (btrim(target_id) <> ''),
    CONSTRAINT chk_catalog_admin_audit_before
        CHECK (before IS NULL OR jsonb_typeof(before) = 'object'),
    CONSTRAINT chk_catalog_admin_audit_after
        CHECK (after IS NULL OR jsonb_typeof(after) = 'object')
);

CREATE INDEX IF NOT EXISTS idx_catalog_admin_audit_target
    ON catalog_admin_audit (target_type, target_id, created_at DESC);

CREATE FUNCTION catalog_admin_audit_reject_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'catalog_admin_audit is append-only: % is not allowed', TG_OP
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_catalog_admin_audit_append_only
    BEFORE UPDATE OR DELETE ON catalog_admin_audit
    FOR EACH ROW
    EXECUTE FUNCTION catalog_admin_audit_reject_change();
