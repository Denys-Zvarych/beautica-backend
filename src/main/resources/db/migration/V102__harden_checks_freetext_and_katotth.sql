-- V102: defense-in-depth CHECK constraints for fields previously guarded only at
-- the DTO (Bean Validation) / @Column layer. A raw-SQL / seed / future-admin
-- insert bypasses the application layer entirely; these CHECKs make the bad row
-- impossible at the storage layer too (Anti-Bug Playbook §O).
--
-- Fix-forward only: shipped migrations (V1..V101) are immutable. Every predicate
-- below is satisfiable by ALL existing rows so the migration cannot crash-loop
-- Flyway on deploy or fail the Testcontainers IT boot:
--   * reviews / users / salons free-text columns are NULL or app-validated
--     (no migration seeds them; prod/IT tables are clean at migration time).
--   * All 454 seeded KATOTTH codes (V53) match ^UA[0-9]{17}$ (verified).
--
-- Predicates are NULL-safe: `col !~ ...` and `col IS NULL OR ...` evaluate to
-- UNKNOWN/TRUE for NULL, which a CHECK constraint accepts.

-- ============================================================================
-- reviews.comment — when present, must not be blank / all-whitespace
-- Mirrors CreateReviewRequest: @Size(min=1) + @Pattern requiring a leading
-- visible character. NULL ("no comment") stays valid.
-- ============================================================================
ALTER TABLE reviews
    ADD CONSTRAINT chk_reviews_comment_not_blank
        CHECK (comment IS NULL OR btrim(comment) <> '');

-- ============================================================================
-- KATOTTH code format — oblasts / cities / city_districts
-- Mirrors the official classifier shape used by the V53 seed: literal "UA"
-- followed by exactly 17 digits (total length 19). The existing V52
-- chk_*_katotth_code_nonempty constraints (col <> '') are kept; this adds the
-- format guard so a malformed reference-data insert is rejected.
-- The hex-escape-free [0-9] form avoids Postgres \x greediness pitfalls.
-- ============================================================================
ALTER TABLE oblasts
    ADD CONSTRAINT chk_oblasts_katotth_code_format
        CHECK (katotth_code ~ '^UA[0-9]{17}$');

ALTER TABLE cities
    ADD CONSTRAINT chk_cities_katotth_code_format
        CHECK (katotth_code ~ '^UA[0-9]{17}$');

ALTER TABLE city_districts
    ADD CONSTRAINT chk_city_districts_katotth_code_format
        CHECK (katotth_code ~ '^UA[0-9]{17}$');

-- ============================================================================
-- users free-text address fields — reject ASCII control characters
-- Mirrors the DTO @Pattern("^[^\\p{Cntrl}]*$") on IndependentMasterUpdateRequest
-- (Java \p{Cntrl} = ASCII [\x00-\x1F\x7F]). The bracketed hex range is safe from
-- \x greediness because every escape is followed by a non-hex char (-, \, ]).
-- ============================================================================
ALTER TABLE users
    ADD CONSTRAINT chk_users_street_no_ctrl
        CHECK (street !~ '[\x00-\x1F\x7F]');
ALTER TABLE users
    ADD CONSTRAINT chk_users_building_no_no_ctrl
        CHECK (building_no !~ '[\x00-\x1F\x7F]');
ALTER TABLE users
    ADD CONSTRAINT chk_users_location_note_no_ctrl
        CHECK (location_note !~ '[\x00-\x1F\x7F]');

-- ============================================================================
-- salons free-text address fields — identical exposure to users (same DTO
-- @Pattern on Create/UpdateSalonRequest). Hardened together to avoid leaving an
-- equivalent hole open while hardening users.
-- ============================================================================
ALTER TABLE salons
    ADD CONSTRAINT chk_salons_street_no_ctrl
        CHECK (street !~ '[\x00-\x1F\x7F]');
ALTER TABLE salons
    ADD CONSTRAINT chk_salons_building_no_no_ctrl
        CHECK (building_no !~ '[\x00-\x1F\x7F]');
ALTER TABLE salons
    ADD CONSTRAINT chk_salons_location_note_no_ctrl
        CHECK (location_note !~ '[\x00-\x1F\x7F]');
