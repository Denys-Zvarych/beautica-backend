-- Phase 12.1 fix: SalonRepository.existsByOwnerId(UUID) must find any salon for the given
-- owner (active OR inactive) to correctly decide is_primary on salon creation.
-- V46's idx_salons_owner_active_created is partial (WHERE is_active = true) and
-- idx_salons_owner_primary (V56) is partial (WHERE is_primary = true) — neither serves
-- the unfiltered WHERE owner_id = ? predicate. V47 dropped the old idx_salons_owner_id;
-- this restores it as a non-partial index for the existsByOwnerId query path.
CREATE INDEX IF NOT EXISTS idx_salons_owner_id
    ON salons (owner_id);
