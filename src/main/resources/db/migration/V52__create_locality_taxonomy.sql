-- V52: locality taxonomy — oblasts, cities, city_districts (Phase 10.1)
--
-- Three controlled-vocabulary tables keyed by the official KATOTTH code.
-- Urban districts (city_districts) represent KATOTTH category "B" rows — i.e.
-- районы міста (Kyiv 10, Kharkiv 9, Lviv 6, Odesa 4, Dnipro 8, etc.).
-- They are NOT oblast-level raions.
--
-- Surrogate UUID PKs are used for FK joins from users/salons (Phase 10.3).
-- KATOTTH codes are retained as the stable external business key (UNIQUE NOT NULL)
-- to support idempotent upsert-style seed migrations and future "liberated
-- territory" one-row adds.
--
-- No spatial/PostGIS columns — this is non-spatial reference data (Part A).
-- Spatial extensions are deferred to Part B.
-- No seed data — data load is Phase 10.2.

-- ============================================================================
-- TABLE: oblasts
-- ============================================================================
CREATE TABLE oblasts (
    id           UUID        NOT NULL DEFAULT gen_random_uuid(),
    katotth_code VARCHAR(20) NOT NULL,
    name_uk      VARCHAR(255) NOT NULL,
    name_en      VARCHAR(255) NOT NULL,

    CONSTRAINT pk_oblasts PRIMARY KEY (id),
    CONSTRAINT uq_oblasts_katotth_code UNIQUE (katotth_code),
    CONSTRAINT chk_oblasts_katotth_code_nonempty CHECK (katotth_code <> ''),
    CONSTRAINT chk_oblasts_name_uk_nonempty CHECK (name_uk <> ''),
    CONSTRAINT chk_oblasts_name_en_nonempty CHECK (name_en <> '')
);

-- ============================================================================
-- TABLE: cities
-- ============================================================================
CREATE TABLE cities (
    id           UUID        NOT NULL DEFAULT gen_random_uuid(),
    oblast_id    UUID        NOT NULL,
    katotth_code VARCHAR(20) NOT NULL,
    name_uk      VARCHAR(255) NOT NULL,
    name_en      VARCHAR(255) NOT NULL,

    CONSTRAINT pk_cities PRIMARY KEY (id),
    CONSTRAINT uq_cities_katotth_code UNIQUE (katotth_code),
    CONSTRAINT fk_cities_oblast FOREIGN KEY (oblast_id) REFERENCES oblasts (id),
    CONSTRAINT chk_cities_katotth_code_nonempty CHECK (katotth_code <> ''),
    CONSTRAINT chk_cities_name_uk_nonempty CHECK (name_uk <> ''),
    CONSTRAINT chk_cities_name_en_nonempty CHECK (name_en <> '')
);

-- Composite index — drives "cities by oblast" picker query with ORDER BY name_uk
CREATE INDEX idx_cities_oblast_id ON cities (oblast_id, name_uk);

-- ============================================================================
-- TABLE: city_districts
-- ============================================================================
CREATE TABLE city_districts (
    id           UUID        NOT NULL DEFAULT gen_random_uuid(),
    city_id      UUID        NOT NULL,
    katotth_code VARCHAR(20) NOT NULL,
    name_uk      VARCHAR(255) NOT NULL,
    name_en      VARCHAR(255) NOT NULL,

    CONSTRAINT pk_city_districts PRIMARY KEY (id),
    CONSTRAINT uq_city_districts_katotth_code UNIQUE (katotth_code),
    CONSTRAINT fk_city_districts_city FOREIGN KEY (city_id) REFERENCES cities (id),
    CONSTRAINT chk_city_districts_katotth_code_nonempty CHECK (katotth_code <> ''),
    CONSTRAINT chk_city_districts_name_uk_nonempty CHECK (name_uk <> ''),
    CONSTRAINT chk_city_districts_name_en_nonempty CHECK (name_en <> '')
);

-- Composite index — drives "urban districts by city" picker and the
-- district-primary search filter (Phase 10.5) with ORDER BY name_uk
CREATE INDEX idx_city_districts_city_id ON city_districts (city_id, name_uk);
