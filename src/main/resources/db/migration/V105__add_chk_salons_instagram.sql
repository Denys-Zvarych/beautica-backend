-- V105: defense-in-depth CHECK constraint for salons.instagram_url — second line
-- of defense mirroring V61's chk_users_instagram, now that CreateSalonRequest /
-- UpdateSalonRequest accept the same bare-handle-or-URL grammar as users.instagram.
-- A raw-SQL / seed / future-admin backfill bypasses the DTO @Pattern entirely; this
-- CHECK makes an out-of-grammar row impossible at the storage layer too
-- (Anti-Bug Playbook §O).
--
-- Fix-forward only: shipped migrations (V1..V104) are immutable. All existing
-- salons.instagram_url values were written under the PRE-widening validation,
-- which only ever produced the full "https://instagram.com/<handle>" form — that
-- already satisfies the `LIKE 'https://instagram.com/%'` branch below, so this
-- migration is additive/safe against current data with no backfill required.
--
-- Predicate is NULL-safe: `instagram_url IS NULL OR ...` evaluates to TRUE for
-- NULL, which a CHECK constraint accepts.
ALTER TABLE salons
    ADD CONSTRAINT chk_salons_instagram
        CHECK (instagram_url IS NULL
            OR instagram_url ~ '^@?[A-Za-z0-9._]{1,30}$'
            OR instagram_url LIKE 'https://instagram.com/%'
            OR instagram_url LIKE 'https://www.instagram.com/%');
