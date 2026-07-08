-- V110: add professional_title to users
-- professional_title — free-text professional headline/label chosen by the provider
--   (e.g. "Майстер манікюру", "Візажист"). Distinct from bio (long-form description)
--   and from the role enum. Every role except CLIENT may set it; the CLIENT guard is
--   enforced in the service layer (UserService.updateProfile), not the DB.
-- VARCHAR(100) mirrors @Column(length = 100) on User.professionalTitle.
-- Nullable — no backfill required for existing rows; providers with no title return null.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS professional_title VARCHAR(100);

-- Control-char CHECK, mirroring chk_users_instagram (V61) for instagram/bio parity.
-- NULL-allowed; rejects any ASCII C0 control (0x00–0x1F) or DEL (0x7F). This is a
-- coarse DB backstop UNDER the stricter application @Pattern (which also blocks
-- zero-width / bidi Unicode spoofing chars) — the DB never has to be the last line.
-- DROP … IF EXISTS keeps a re-apply / clean-DB replay idempotent (this migration is
-- unshipped; edited in place rather than adding a V111).
ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_professional_title;
ALTER TABLE users
    ADD CONSTRAINT chk_users_professional_title
        CHECK (professional_title IS NULL
            OR professional_title !~ '[\x00-\x1F\x7F]');
