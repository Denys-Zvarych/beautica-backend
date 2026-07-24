-- Phase 13.6 (perf follow-up): supporting indexes for the public salon-reviews and
-- salon-catalog endpoints.
--
-- ── reviews — rating-sorted salon review pages ───────────────────────────────
-- The only existing salon-scoped index is idx_reviews_salon_created(salon_id,
-- created_at DESC) WHERE salon_id IS NOT NULL (V41), which only serves the
-- NEWEST/OLDEST sorts of ReviewRepository#findIdsBySalonIdOrderByCreatedAtDesc/Asc.
-- It does not serve the HIGHEST/LOWEST sorts
-- (findIdsBySalonIdOrderByRatingDescCreatedAtDesc / ...RatingAscCreatedAtDesc) or the
-- rating-bucket GROUP BY in countBySalonIdGroupByRating.
--
-- TWO indexes are added, not one: a single (rating DESC, created_at DESC) index
-- backward-scanned yields (rating ASC, created_at ASC) — that does NOT match the
-- LOWEST sort's tiebreak of created_at DESC. Each sort's exact (direction, tiebreak)
-- pair needs its own index to avoid a heap-level re-sort.
CREATE INDEX idx_reviews_salon_rating_desc ON reviews(salon_id, rating DESC, created_at DESC)
    WHERE salon_id IS NOT NULL;
CREATE INDEX idx_reviews_salon_rating_asc  ON reviews(salon_id, rating ASC,  created_at DESC)
    WHERE salon_id IS NOT NULL;

-- ── master_services — partial index for the active-row EXISTS subquery ───────
-- idx_master_services_service_def(service_def_id) (V7) is a plain index, unlike its
-- sibling idx_master_services_master_active(master_id) WHERE is_active = true (V36).
-- ServiceRepository#findBookableServicesBySalon's EXISTS subquery filters on
-- msa.serviceDefinition = sd AND msa.isActive = true, so a partial index matching
-- that predicate lets the planner satisfy both from the index alone.
--
-- The plain index is intentionally KEPT (not dropped): MasterServiceRepository
-- #findMasterIdsByServiceDefinitionId (used for targeted cache eviction in
-- ServiceCatalogService#deactivateServiceDefinition/updateServiceDefinition/
-- updateServicePhoto) queries by service_def_id WITHOUT an is_active filter — it
-- must also match INACTIVE master_services rows so a deactivated assignment's
-- master still gets its stale cache entry evicted. Dropping the plain index would
-- regress that query to a sequential scan for inactive rows.
CREATE INDEX idx_master_services_service_def_active ON master_services(service_def_id)
    WHERE is_active = true;
