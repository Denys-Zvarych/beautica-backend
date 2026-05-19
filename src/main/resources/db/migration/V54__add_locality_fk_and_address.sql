-- V54: locality FK + light address columns on users & salons (Phase 10.3)
--
-- Attaches the Phase 10.1 taxonomy (cities / city_districts, seeded by Phase
-- 10.2) to the rows it discovers — users and salons — via NULLABLE FK columns,
-- plus light structured street fields.
--
-- ---------------------------------------------------------------------------
-- M1 rationale (forward-compat mitigation)
-- ---------------------------------------------------------------------------
-- `street` and `building_no` are stored as *separate light fields* (not one
-- opaque address blob), and `location_note` (landmark / how-to-find) is a
-- *separate* free-text field. No validation, no geocoding is performed now.
-- Keeping them structured + separate gives a future geocoder (deferred Part B)
-- a clean tuple — street + building_no + city + district — yielding high match
-- confidence and a small manual-review queue when Part B resumes, instead of
-- having to re-parse a single free-text address string.
--
-- ---------------------------------------------------------------------------
-- Step 3 rationale — NO permanent backfill, NO environment-specific data fix
-- ---------------------------------------------------------------------------
-- This migration ships ZERO data DML. The FK columns are added NULLABLE
-- precisely so the existing single Neon dev DB's throwaway rows do not violate
-- a constraint. Every future environment (separate dev instance, prod) is born
-- clean — created AFTER the taxonomy (10.1/10.2) and write-path validation
-- (10.6) exist — so it never contains dirty free-text locality data. The only
-- dirty rows are disposable dev/test rows in today's DB; the correct remedy is
-- a one-off, dev-only manual cleanup / test-data re-seed run by hand —
-- explicitly NOT a permanent Flyway migration (which would be dead no-op code
-- on every future clean environment). The old "fuzzy match + alias table +
-- unmatched report" idea is deleted and is NOT implemented. Promotion of
-- provider city_id to NOT NULL going forward is owned by Phase 10.6
-- (write-path validation), not by a data-altering migration. The permanent
-- migration chain (V52/V53/V54 + later) stays environment-agnostic.
--
-- Legacy free-text columns (salons.city/region/address, users.city/region)
-- are kept and left NULLABLE — not dropped — forward-compatible with Part B's
-- later embeddable that attaches to these same rows. They are already nullable
-- (V3/V4 for salons, V35 for users), so no relax-to-NULL ALTER is required.

-- ============================================================================
-- users — locality FK + light address columns (all NULLABLE)
-- ============================================================================
ALTER TABLE users ADD COLUMN city_id       UUID;
ALTER TABLE users ADD COLUMN district_id   UUID;
ALTER TABLE users ADD COLUMN street        VARCHAR(255);
ALTER TABLE users ADD COLUMN building_no   VARCHAR(50);
ALTER TABLE users ADD COLUMN location_note TEXT;

ALTER TABLE users
    ADD CONSTRAINT fk_users_city_id
        FOREIGN KEY (city_id) REFERENCES cities (id);
ALTER TABLE users
    ADD CONSTRAINT fk_users_district_id
        FOREIGN KEY (district_id) REFERENCES city_districts (id);

CREATE INDEX idx_users_city_id     ON users (city_id);
CREATE INDEX idx_users_district_id ON users (district_id);

-- ============================================================================
-- salons — locality FK + light address columns (all NULLABLE)
-- ============================================================================
ALTER TABLE salons ADD COLUMN city_id       UUID;
ALTER TABLE salons ADD COLUMN district_id   UUID;
ALTER TABLE salons ADD COLUMN street        VARCHAR(255);
ALTER TABLE salons ADD COLUMN building_no   VARCHAR(50);
ALTER TABLE salons ADD COLUMN location_note TEXT;

ALTER TABLE salons
    ADD CONSTRAINT fk_salons_city_id
        FOREIGN KEY (city_id) REFERENCES cities (id);
ALTER TABLE salons
    ADD CONSTRAINT fk_salons_district_id
        FOREIGN KEY (district_id) REFERENCES city_districts (id);

CREATE INDEX idx_salons_city_id     ON salons (city_id);
CREATE INDEX idx_salons_district_id ON salons (district_id);
