package com.beautica.salon.repository;

import com.beautica.salon.entity.Salon;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Salon data access, including the six native discovery projections behind
 * {@code SearchService.findSalonsByLocation} (the salon name / category / price
 * search facets — the per-service-type facet is built dynamically in
 * {@code SearchService}).
 *
 * <p><b>Bookable row-source (salon service-type fix).</b> A salon's client-visible
 * offering is <em>only</em> what its masters actually perform — it mirrors the
 * booking catalogue ({@code ServiceRepository.findBookableServicesBySalon}). So
 * every salon-owned {@code service_definitions} row that feeds discovery (the
 * category-match gate, the {@code ?q} service-name gate, the {@code serviceNames}
 * preview and the price band) is additionally gated by
 * {@code EXISTS(active master_services on an active master)}. A salon that owns a
 * service no active master performs must not match, must not price off it, and
 * must not list its name. The price band itself is the MIN–MAX of the performing
 * masters' EFFECTIVE prices ({@code COALESCE(price_override, base_price)} floor,
 * {@code COALESCE(price_override, RANGE ceiling)} ceiling) — never the raw
 * salon-owned {@code base_price} — so a per-master override shows through and an
 * unbookable owned service never drags the band.
 */
public interface SalonRepository extends JpaRepository<Salon, UUID> {

    List<Salon> findAllByOwnerIdAndIsActiveTrue(UUID ownerId);

    /**
     * Returns the oldest active salon for the given owner, ordered by {@code created_at ASC}.
     *
     * <p>Used by {@code MediaService.resolvePortfolioTarget} to deterministically select
     * which salon receives a portfolio upload when an owner has more than one active salon.
     * The {@code findTop} prefix limits the DB result to a single row, avoiding a
     * non-deterministic {@code salons.get(0)} pick on an unordered list (Perf MEDIUM F6).
     */
    Optional<Salon> findTopByOwnerIdAndIsActiveTrueOrderByCreatedAtAsc(UUID ownerId);

    /**
     * Returns the IDs of all active salons owned by the given owner.
     *
     * <p>Used by {@code DashboardService.resolveScope} to collect every salon the SALON_OWNER
     * is allowed to see so that multi-salon owners receive revenue data across all their salons,
     * not just the oldest one (FIX 1 — HIGH security/correctness finding).
     */
    @Query("SELECT s.id FROM Salon s WHERE s.owner.id = :ownerId AND s.isActive = true")
    List<UUID> findIdsByOwnerIdAndIsActiveTrue(@Param("ownerId") UUID ownerId);

    boolean existsByIdAndOwnerId(UUID id, UUID ownerId);

    /**
     * Lightweight owner-id projection (Phase 21.3 rotation PERF fix). Used by
     * {@code AuthorizationService.salonsShareOwner} to resolve the source salon's owner without
     * hydrating the full {@code Salon} entity (~20 columns) for a single FK read — mirrors the
     * existing {@code findOwnerUserId}-style projection pattern used elsewhere in this repository
     * layer (e.g. {@code ServiceRepository.findOwnerUserId}).
     */
    @Query("SELECT s.owner.id FROM Salon s WHERE s.id = :salonId")
    Optional<UUID> findOwnerIdById(@Param("salonId") UUID salonId);

    /**
     * Lightweight existence + active-flag check (Phase 21.3 rotation PERF fix). Used by
     * {@code SalonService.rotateAdmin} to validate the destination salon without loading the full
     * entity — the destination UUID is written directly onto {@code User.salonId} and no entity
     * fields are otherwise needed. Returns {@code false} for both "does not exist" and "exists but
     * inactive", which the caller treats identically (Anti-Bug §D — no destination-status oracle).
     */
    boolean existsByIdAndIsActiveTrue(UUID id);

    // True iff the given owner already has at least one salon (primary or not).
    // Used in SalonService.createSalon to decide is_primary = true/false.
    boolean existsByOwnerId(UUID ownerId);

    // Returns the owner's primary salon, if one exists.
    Optional<Salon> findByOwnerIdAndIsPrimaryTrue(UUID ownerId);

    Optional<Salon> findByIdAndOwnerId(UUID id, UUID ownerId);

    @Query("SELECT s FROM Salon s JOIN FETCH s.owner WHERE s.id = :id AND s.isActive = true")
    Optional<Salon> findByIdAndIsActiveTrueWithOwner(@Param("id") UUID id);

    /**
     * Active salons in a specific discovery district (district-primary branch
     * of the Phase 10.5 FK location filter).
     *
     * <p><b>Plan shape (Phase 10.8, MEDIUM-1):</b> a single-column equality on
     * {@code district_id} under a constant {@code is_active = true}. No
     * disjunctive NULL-guard OR-chain spanning two columns, so Postgres can
     * index-serve it via {@code idx_salons_district_id} (V54) at any scale —
     * the predicate is SARGable. {@link SearchService#searchSalons} dispatches
     * here when a districtId is resolved; the caller never passes
     * {@code null}.
     *
     * <p>Replaces the removed free-text {@code findByFilter} (exact
     * string-equality on {@code city}/{@code region} — the Phase 10.5 bug);
     * no legacy variant is kept alongside (§E). A salon's discovery locality
     * is its own {@code district_id} — there is no salon-to-salon link to
     * resolve through (that resolution is the master-only
     * {@code SALON_MASTER → salon} case). The {@code Page} return type makes
     * Spring Data emit the matching {@code COUNT(*)} companion.
     *
     * <p><b>§E — search path MUST use the projection overload.</b>
     * {@link SearchService} must call
     * {@link #findActiveByDistrictIdAsProjection(UUID, String, String, java.math.BigDecimal, java.math.BigDecimal, Pageable)} so
     * only the columns needed by {@link SalonSearchProjection} are fetched.
     * This full-entity overload remains for non-search callers that genuinely
     * need the full {@code Salon} graph.
     *
     * @param districtId resolved discovery district id (never {@code null})
     */
    @Query("""
            SELECT s FROM Salon s
            WHERE s.isActive = true
              AND s.districtId = :districtId
            """)
    Page<Salon> findActiveByDistrictId(
            @Param("districtId") UUID districtId,
            Pageable pageable
    );

    /**
     * Projection overload of {@link #findActiveByDistrictId}: fetches only the
     * columns needed for salon discovery plus the bookable price band and
     * category-scoped {@code serviceNames} preview — no full entity hydration,
     * no lazy proxy for the {@code owner} association.
     *
     * <p>Used exclusively by {@link SearchService#findSalonsByLocation} to
     * eliminate the LOW PERF finding ("Loads Salon entity then maps via
     * Page#map"). The price/name LATERAL and the category / {@code q}
     * service-name gates are all restricted to <em>bookable</em> salon-owned
     * services (see the interface-level Javadoc).
     *
     * @param districtId resolved discovery district id (never {@code null})
     */
    @Query(value = """
            SELECT t.id           AS id,
                   t.name         AS name,
                   t.city_id      AS city_id,
                   t.district_id  AS district_id,
                   t.avatar_url   AS avatar_url,
                   t.price_min    AS price_min,
                   t.price_max    AS price_max,
                   pn.pnames      AS service_names,
                   t.street       AS street,
                   t.building_no  AS building_no,
                   t.location_note AS location_note
            FROM (
                SELECT s.id           AS id,
                       s.name         AS name,
                       s.city_id      AS city_id,
                       s.district_id  AS district_id,
                       s.avatar_url   AS avatar_url,
                       pr.pmin        AS price_min,
                       pr.pmax        AS price_max,
                       s.street       AS street,
                       s.building_no  AS building_no,
                       s.location_note AS location_note
                FROM salons s
                LEFT JOIN LATERAL (
                    SELECT MIN(COALESCE(ms.price_override, sd.base_price)) AS pmin,
                           MAX(COALESCE(ms.price_override,
                                        CASE WHEN sd.price_type = 'RANGE'
                                             THEN sd.price_max ELSE sd.base_price END)) AS pmax
                    FROM master_services ms
                    JOIN service_definitions sd ON sd.id = ms.service_def_id AND sd.is_active = true
                    JOIN masters mad ON mad.id = ms.master_id AND mad.is_active = true
                    WHERE sd.owner_type = 'SALON'
                      AND sd.owner_id = s.id
                      AND ms.is_active = true
                      AND (CAST(:category AS text) IS NULL OR sd.category = CAST(:category AS text))
                ) pr ON true
                WHERE s.is_active = true
                  AND s.district_id = :districtId
                  AND (CAST(:category AS text) IS NULL OR EXISTS (
                      SELECT 1 FROM service_definitions sdc
                      WHERE sdc.owner_type = 'SALON'
                        AND sdc.owner_id = s.id
                        AND sdc.is_active = true
                        AND EXISTS (SELECT 1 FROM master_services msc
                                    JOIN masters mmc ON mmc.id = msc.master_id AND mmc.is_active = true
                                    WHERE msc.service_def_id = sdc.id AND msc.is_active = true)
                        AND sdc.category = CAST(:category AS text)))
                  AND (CAST(:q AS text) IS NULL
                       OR s.name ILIKE CAST(:q AS text)
                       OR EXISTS (SELECT 1
                                  FROM service_definitions sdq
                                  WHERE sdq.owner_type = 'SALON'
                                    AND sdq.owner_id = s.id
                                    AND sdq.is_active = true
                                    AND EXISTS (SELECT 1 FROM master_services msq
                                                JOIN masters mmq ON mmq.id = msq.master_id AND mmq.is_active = true
                                                WHERE msq.service_def_id = sdq.id AND msq.is_active = true)
                                    AND sdq.name ILIKE CAST(:q AS text)))
                  AND (CAST(:minPrice AS numeric) IS NULL OR pr.pmax >= CAST(:minPrice AS numeric))
                  AND (CAST(:maxPrice AS numeric) IS NULL OR pr.pmin <= CAST(:maxPrice AS numeric))
            ) t
            LEFT JOIN LATERAL (
                SELECT array_agg(z.name) AS pnames
                  FROM (SELECT DISTINCT sd2.name AS name
                        FROM service_definitions sd2
                        WHERE sd2.owner_type = 'SALON'
                          AND sd2.owner_id = t.id
                          AND sd2.is_active = true
                          AND EXISTS (SELECT 1 FROM master_services ms2
                                      JOIN masters mm2 ON mm2.id = ms2.master_id AND mm2.is_active = true
                                      WHERE ms2.service_def_id = sd2.id AND ms2.is_active = true)
                          AND (CAST(:category AS text) IS NULL OR sd2.category = CAST(:category AS text))
                        ORDER BY sd2.name
                        LIMIT 3) z) pn ON true
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM salons s
            LEFT JOIN LATERAL (
                SELECT MIN(COALESCE(ms.price_override, sd.base_price)) AS pmin,
                       MAX(COALESCE(ms.price_override,
                                    CASE WHEN sd.price_type = 'RANGE'
                                         THEN sd.price_max ELSE sd.base_price END)) AS pmax
                FROM master_services ms
                JOIN service_definitions sd ON sd.id = ms.service_def_id AND sd.is_active = true
                JOIN masters mad ON mad.id = ms.master_id AND mad.is_active = true
                WHERE sd.owner_type = 'SALON'
                  AND sd.owner_id = s.id
                  AND ms.is_active = true
                  AND (CAST(:category AS text) IS NULL OR sd.category = CAST(:category AS text))
            ) pr ON true
            WHERE s.is_active = true
              AND s.district_id = :districtId
              AND (CAST(:category AS text) IS NULL OR EXISTS (
                  SELECT 1 FROM service_definitions sdc
                  WHERE sdc.owner_type = 'SALON'
                    AND sdc.owner_id = s.id
                    AND sdc.is_active = true
                    AND EXISTS (SELECT 1 FROM master_services msc
                                JOIN masters mmc ON mmc.id = msc.master_id AND mmc.is_active = true
                                WHERE msc.service_def_id = sdc.id AND msc.is_active = true)
                    AND sdc.category = CAST(:category AS text)))
              AND (CAST(:q AS text) IS NULL
                   OR s.name ILIKE CAST(:q AS text)
                   OR EXISTS (SELECT 1
                              FROM service_definitions sdq
                              WHERE sdq.owner_type = 'SALON'
                                AND sdq.owner_id = s.id
                                AND sdq.is_active = true
                                AND EXISTS (SELECT 1 FROM master_services msq
                                            JOIN masters mmq ON mmq.id = msq.master_id AND mmq.is_active = true
                                            WHERE msq.service_def_id = sdq.id AND msq.is_active = true)
                                AND sdq.name ILIKE CAST(:q AS text)))
              AND (CAST(:minPrice AS numeric) IS NULL OR pr.pmax >= CAST(:minPrice AS numeric))
              AND (CAST(:maxPrice AS numeric) IS NULL OR pr.pmin <= CAST(:maxPrice AS numeric))
            """,
            nativeQuery = true)
    Page<SalonSearchProjection> findActiveByDistrictIdAsProjection(
            @Param("districtId") UUID districtId,
            @Param("category") String category,
            @Param("q") String q,
            @Param("minPrice") java.math.BigDecimal minPrice,
            @Param("maxPrice") java.math.BigDecimal maxPrice,
            Pageable pageable
    );

    /**
     * No-price-bound variant of {@link #findActiveByDistrictIdAsProjection}
     * (HIGH PERF gate). The data query is identical (the price-range LATERAL
     * still runs because {@code priceMin}/{@code priceMax} are display columns on
     * {@link SalonSearchProjection}), but the {@code countQuery} is a plain
     * {@code COUNT(*)} with <b>no LATERAL</b>: when both price bounds are null
     * the per-salon price aggregate is irrelevant to the count, and paying for it
     * on every search was a pure regression. {@link SearchService} dispatches
     * here when {@code minPrice == null && maxPrice == null}.
     *
     * @param districtId resolved discovery district id (never {@code null})
     */
    @Query(value = """
            SELECT t.id           AS id,
                   t.name         AS name,
                   t.city_id      AS city_id,
                   t.district_id  AS district_id,
                   t.avatar_url   AS avatar_url,
                   t.price_min    AS price_min,
                   t.price_max    AS price_max,
                   pn.pnames      AS service_names,
                   t.street       AS street,
                   t.building_no  AS building_no,
                   t.location_note AS location_note
            FROM (
                SELECT s.id           AS id,
                       s.name         AS name,
                       s.city_id      AS city_id,
                       s.district_id  AS district_id,
                       s.avatar_url   AS avatar_url,
                       pr.pmin        AS price_min,
                       pr.pmax        AS price_max,
                       s.street       AS street,
                       s.building_no  AS building_no,
                       s.location_note AS location_note
                FROM salons s
                LEFT JOIN LATERAL (
                    SELECT MIN(COALESCE(ms.price_override, sd.base_price)) AS pmin,
                           MAX(COALESCE(ms.price_override,
                                        CASE WHEN sd.price_type = 'RANGE'
                                             THEN sd.price_max ELSE sd.base_price END)) AS pmax
                    FROM master_services ms
                    JOIN service_definitions sd ON sd.id = ms.service_def_id AND sd.is_active = true
                    JOIN masters mad ON mad.id = ms.master_id AND mad.is_active = true
                    WHERE sd.owner_type = 'SALON'
                      AND sd.owner_id = s.id
                      AND ms.is_active = true
                      AND (CAST(:category AS text) IS NULL OR sd.category = CAST(:category AS text))
                ) pr ON true
                WHERE s.is_active = true
                  AND s.district_id = :districtId
                  AND (CAST(:category AS text) IS NULL OR EXISTS (
                      SELECT 1 FROM service_definitions sdc
                      WHERE sdc.owner_type = 'SALON'
                        AND sdc.owner_id = s.id
                        AND sdc.is_active = true
                        AND EXISTS (SELECT 1 FROM master_services msc
                                    JOIN masters mmc ON mmc.id = msc.master_id AND mmc.is_active = true
                                    WHERE msc.service_def_id = sdc.id AND msc.is_active = true)
                        AND sdc.category = CAST(:category AS text)))
                  AND (CAST(:q AS text) IS NULL
                       OR s.name ILIKE CAST(:q AS text)
                       OR EXISTS (SELECT 1
                                  FROM service_definitions sdq
                                  WHERE sdq.owner_type = 'SALON'
                                    AND sdq.owner_id = s.id
                                    AND sdq.is_active = true
                                    AND EXISTS (SELECT 1 FROM master_services msq
                                                JOIN masters mmq ON mmq.id = msq.master_id AND mmq.is_active = true
                                                WHERE msq.service_def_id = sdq.id AND msq.is_active = true)
                                    AND sdq.name ILIKE CAST(:q AS text)))
            ) t
            LEFT JOIN LATERAL (
                SELECT array_agg(z.name) AS pnames
                  FROM (SELECT DISTINCT sd2.name AS name
                        FROM service_definitions sd2
                        WHERE sd2.owner_type = 'SALON'
                          AND sd2.owner_id = t.id
                          AND sd2.is_active = true
                          AND EXISTS (SELECT 1 FROM master_services ms2
                                      JOIN masters mm2 ON mm2.id = ms2.master_id AND mm2.is_active = true
                                      WHERE ms2.service_def_id = sd2.id AND ms2.is_active = true)
                          AND (CAST(:category AS text) IS NULL OR sd2.category = CAST(:category AS text))
                        ORDER BY sd2.name
                        LIMIT 3) z) pn ON true
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM salons s
            WHERE s.is_active = true
              AND s.district_id = :districtId
              AND (CAST(:category AS text) IS NULL OR EXISTS (
                  SELECT 1 FROM service_definitions sdc
                  WHERE sdc.owner_type = 'SALON'
                    AND sdc.owner_id = s.id
                    AND sdc.is_active = true
                    AND EXISTS (SELECT 1 FROM master_services msc
                                JOIN masters mmc ON mmc.id = msc.master_id AND mmc.is_active = true
                                WHERE msc.service_def_id = sdc.id AND msc.is_active = true)
                    AND sdc.category = CAST(:category AS text)))
              AND (CAST(:q AS text) IS NULL
                   OR s.name ILIKE CAST(:q AS text)
                   OR EXISTS (SELECT 1
                              FROM service_definitions sdq
                              WHERE sdq.owner_type = 'SALON'
                                AND sdq.owner_id = s.id
                                AND sdq.is_active = true
                                AND EXISTS (SELECT 1 FROM master_services msq
                                            JOIN masters mmq ON mmq.id = msq.master_id AND mmq.is_active = true
                                            WHERE msq.service_def_id = sdq.id AND msq.is_active = true)
                                AND sdq.name ILIKE CAST(:q AS text)))
            """,
            nativeQuery = true)
    Page<SalonSearchProjection> findActiveByDistrictIdNoPriceAsProjection(
            @Param("districtId") UUID districtId,
            @Param("category") String category,
            @Param("q") String q,
            Pageable pageable
    );

    /**
     * Active salons in a specific discovery city (city-only branch of the
     * Phase 10.5 FK location filter — a districted city without a resolved
     * district widens to city level on the read side).
     *
     * <p><b>Plan shape (Phase 10.8, MEDIUM-1):</b> a single-column equality on
     * {@code city_id} under a constant {@code is_active = true}, SARGable and
     * index-served by {@code idx_salons_city_id} (V54) at any scale.
     * {@link SearchService#searchSalons} dispatches here when no district was
     * resolved but a cityId is present; the caller never passes {@code null}.
     *
     * <p><b>§E — search path MUST use the projection overload.</b>
     * {@link SearchService} must call
     * {@link #findActiveByCityIdAsProjection(UUID, String, String, java.math.BigDecimal, java.math.BigDecimal, Pageable)}.
     * This full-entity overload remains for non-search callers.
     *
     * @param cityId resolved discovery city id (never {@code null})
     */
    @Query("""
            SELECT s FROM Salon s
            WHERE s.isActive = true
              AND s.cityId = :cityId
            """)
    Page<Salon> findActiveByCityId(
            @Param("cityId") UUID cityId,
            Pageable pageable
    );

    /**
     * Projection overload of {@link #findActiveByCityId}: fetches only the
     * columns needed for salon discovery plus the bookable price band and
     * category-scoped {@code serviceNames} preview.
     *
     * <p>Used exclusively by {@link SearchService#findSalonsByLocation}.
     *
     * @param cityId resolved discovery city id (never {@code null})
     */
    @Query(value = """
            SELECT t.id           AS id,
                   t.name         AS name,
                   t.city_id      AS city_id,
                   t.district_id  AS district_id,
                   t.avatar_url   AS avatar_url,
                   t.price_min    AS price_min,
                   t.price_max    AS price_max,
                   pn.pnames      AS service_names,
                   t.street       AS street,
                   t.building_no  AS building_no,
                   t.location_note AS location_note
            FROM (
                SELECT s.id           AS id,
                       s.name         AS name,
                       s.city_id      AS city_id,
                       s.district_id  AS district_id,
                       s.avatar_url   AS avatar_url,
                       pr.pmin        AS price_min,
                       pr.pmax        AS price_max,
                       s.street       AS street,
                       s.building_no  AS building_no,
                       s.location_note AS location_note
                FROM salons s
                LEFT JOIN LATERAL (
                    SELECT MIN(COALESCE(ms.price_override, sd.base_price)) AS pmin,
                           MAX(COALESCE(ms.price_override,
                                        CASE WHEN sd.price_type = 'RANGE'
                                             THEN sd.price_max ELSE sd.base_price END)) AS pmax
                    FROM master_services ms
                    JOIN service_definitions sd ON sd.id = ms.service_def_id AND sd.is_active = true
                    JOIN masters mad ON mad.id = ms.master_id AND mad.is_active = true
                    WHERE sd.owner_type = 'SALON'
                      AND sd.owner_id = s.id
                      AND ms.is_active = true
                      AND (CAST(:category AS text) IS NULL OR sd.category = CAST(:category AS text))
                ) pr ON true
                WHERE s.is_active = true
                  AND s.city_id = :cityId
                  AND (CAST(:category AS text) IS NULL OR EXISTS (
                      SELECT 1 FROM service_definitions sdc
                      WHERE sdc.owner_type = 'SALON'
                        AND sdc.owner_id = s.id
                        AND sdc.is_active = true
                        AND EXISTS (SELECT 1 FROM master_services msc
                                    JOIN masters mmc ON mmc.id = msc.master_id AND mmc.is_active = true
                                    WHERE msc.service_def_id = sdc.id AND msc.is_active = true)
                        AND sdc.category = CAST(:category AS text)))
                  AND (CAST(:q AS text) IS NULL
                       OR s.name ILIKE CAST(:q AS text)
                       OR EXISTS (SELECT 1
                                  FROM service_definitions sdq
                                  WHERE sdq.owner_type = 'SALON'
                                    AND sdq.owner_id = s.id
                                    AND sdq.is_active = true
                                    AND EXISTS (SELECT 1 FROM master_services msq
                                                JOIN masters mmq ON mmq.id = msq.master_id AND mmq.is_active = true
                                                WHERE msq.service_def_id = sdq.id AND msq.is_active = true)
                                    AND sdq.name ILIKE CAST(:q AS text)))
                  AND (CAST(:minPrice AS numeric) IS NULL OR pr.pmax >= CAST(:minPrice AS numeric))
                  AND (CAST(:maxPrice AS numeric) IS NULL OR pr.pmin <= CAST(:maxPrice AS numeric))
            ) t
            LEFT JOIN LATERAL (
                SELECT array_agg(z.name) AS pnames
                  FROM (SELECT DISTINCT sd2.name AS name
                        FROM service_definitions sd2
                        WHERE sd2.owner_type = 'SALON'
                          AND sd2.owner_id = t.id
                          AND sd2.is_active = true
                          AND EXISTS (SELECT 1 FROM master_services ms2
                                      JOIN masters mm2 ON mm2.id = ms2.master_id AND mm2.is_active = true
                                      WHERE ms2.service_def_id = sd2.id AND ms2.is_active = true)
                          AND (CAST(:category AS text) IS NULL OR sd2.category = CAST(:category AS text))
                        ORDER BY sd2.name
                        LIMIT 3) z) pn ON true
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM salons s
            LEFT JOIN LATERAL (
                SELECT MIN(COALESCE(ms.price_override, sd.base_price)) AS pmin,
                       MAX(COALESCE(ms.price_override,
                                    CASE WHEN sd.price_type = 'RANGE'
                                         THEN sd.price_max ELSE sd.base_price END)) AS pmax
                FROM master_services ms
                JOIN service_definitions sd ON sd.id = ms.service_def_id AND sd.is_active = true
                JOIN masters mad ON mad.id = ms.master_id AND mad.is_active = true
                WHERE sd.owner_type = 'SALON'
                  AND sd.owner_id = s.id
                  AND ms.is_active = true
                  AND (CAST(:category AS text) IS NULL OR sd.category = CAST(:category AS text))
            ) pr ON true
            WHERE s.is_active = true
              AND s.city_id = :cityId
              AND (CAST(:category AS text) IS NULL OR EXISTS (
                  SELECT 1 FROM service_definitions sdc
                  WHERE sdc.owner_type = 'SALON'
                    AND sdc.owner_id = s.id
                    AND sdc.is_active = true
                    AND EXISTS (SELECT 1 FROM master_services msc
                                JOIN masters mmc ON mmc.id = msc.master_id AND mmc.is_active = true
                                WHERE msc.service_def_id = sdc.id AND msc.is_active = true)
                    AND sdc.category = CAST(:category AS text)))
              AND (CAST(:q AS text) IS NULL
                   OR s.name ILIKE CAST(:q AS text)
                   OR EXISTS (SELECT 1
                              FROM service_definitions sdq
                              WHERE sdq.owner_type = 'SALON'
                                AND sdq.owner_id = s.id
                                AND sdq.is_active = true
                                AND EXISTS (SELECT 1 FROM master_services msq
                                            JOIN masters mmq ON mmq.id = msq.master_id AND mmq.is_active = true
                                            WHERE msq.service_def_id = sdq.id AND msq.is_active = true)
                                AND sdq.name ILIKE CAST(:q AS text)))
              AND (CAST(:minPrice AS numeric) IS NULL OR pr.pmax >= CAST(:minPrice AS numeric))
              AND (CAST(:maxPrice AS numeric) IS NULL OR pr.pmin <= CAST(:maxPrice AS numeric))
            """,
            nativeQuery = true)
    Page<SalonSearchProjection> findActiveByCityIdAsProjection(
            @Param("cityId") UUID cityId,
            @Param("category") String category,
            @Param("q") String q,
            @Param("minPrice") java.math.BigDecimal minPrice,
            @Param("maxPrice") java.math.BigDecimal maxPrice,
            Pageable pageable
    );

    /**
     * No-price-bound variant of {@link #findActiveByCityIdAsProjection}
     * (HIGH PERF gate). Plain {@code COUNT(*)} count query (no LATERAL) for the
     * common no-price-filter path; the data query keeps the LATERAL because the
     * price band is a display column. {@link SearchService} dispatches here when
     * both price bounds are null.
     *
     * @param cityId resolved discovery city id (never {@code null})
     */
    @Query(value = """
            SELECT t.id           AS id,
                   t.name         AS name,
                   t.city_id      AS city_id,
                   t.district_id  AS district_id,
                   t.avatar_url   AS avatar_url,
                   t.price_min    AS price_min,
                   t.price_max    AS price_max,
                   pn.pnames      AS service_names,
                   t.street       AS street,
                   t.building_no  AS building_no,
                   t.location_note AS location_note
            FROM (
                SELECT s.id           AS id,
                       s.name         AS name,
                       s.city_id      AS city_id,
                       s.district_id  AS district_id,
                       s.avatar_url   AS avatar_url,
                       pr.pmin        AS price_min,
                       pr.pmax        AS price_max,
                       s.street       AS street,
                       s.building_no  AS building_no,
                       s.location_note AS location_note
                FROM salons s
                LEFT JOIN LATERAL (
                    SELECT MIN(COALESCE(ms.price_override, sd.base_price)) AS pmin,
                           MAX(COALESCE(ms.price_override,
                                        CASE WHEN sd.price_type = 'RANGE'
                                             THEN sd.price_max ELSE sd.base_price END)) AS pmax
                    FROM master_services ms
                    JOIN service_definitions sd ON sd.id = ms.service_def_id AND sd.is_active = true
                    JOIN masters mad ON mad.id = ms.master_id AND mad.is_active = true
                    WHERE sd.owner_type = 'SALON'
                      AND sd.owner_id = s.id
                      AND ms.is_active = true
                      AND (CAST(:category AS text) IS NULL OR sd.category = CAST(:category AS text))
                ) pr ON true
                WHERE s.is_active = true
                  AND s.city_id = :cityId
                  AND (CAST(:category AS text) IS NULL OR EXISTS (
                      SELECT 1 FROM service_definitions sdc
                      WHERE sdc.owner_type = 'SALON'
                        AND sdc.owner_id = s.id
                        AND sdc.is_active = true
                        AND EXISTS (SELECT 1 FROM master_services msc
                                    JOIN masters mmc ON mmc.id = msc.master_id AND mmc.is_active = true
                                    WHERE msc.service_def_id = sdc.id AND msc.is_active = true)
                        AND sdc.category = CAST(:category AS text)))
                  AND (CAST(:q AS text) IS NULL
                       OR s.name ILIKE CAST(:q AS text)
                       OR EXISTS (SELECT 1
                                  FROM service_definitions sdq
                                  WHERE sdq.owner_type = 'SALON'
                                    AND sdq.owner_id = s.id
                                    AND sdq.is_active = true
                                    AND EXISTS (SELECT 1 FROM master_services msq
                                                JOIN masters mmq ON mmq.id = msq.master_id AND mmq.is_active = true
                                                WHERE msq.service_def_id = sdq.id AND msq.is_active = true)
                                    AND sdq.name ILIKE CAST(:q AS text)))
            ) t
            LEFT JOIN LATERAL (
                SELECT array_agg(z.name) AS pnames
                  FROM (SELECT DISTINCT sd2.name AS name
                        FROM service_definitions sd2
                        WHERE sd2.owner_type = 'SALON'
                          AND sd2.owner_id = t.id
                          AND sd2.is_active = true
                          AND EXISTS (SELECT 1 FROM master_services ms2
                                      JOIN masters mm2 ON mm2.id = ms2.master_id AND mm2.is_active = true
                                      WHERE ms2.service_def_id = sd2.id AND ms2.is_active = true)
                          AND (CAST(:category AS text) IS NULL OR sd2.category = CAST(:category AS text))
                        ORDER BY sd2.name
                        LIMIT 3) z) pn ON true
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM salons s
            WHERE s.is_active = true
              AND s.city_id = :cityId
              AND (CAST(:category AS text) IS NULL OR EXISTS (
                  SELECT 1 FROM service_definitions sdc
                  WHERE sdc.owner_type = 'SALON'
                    AND sdc.owner_id = s.id
                    AND sdc.is_active = true
                    AND EXISTS (SELECT 1 FROM master_services msc
                                JOIN masters mmc ON mmc.id = msc.master_id AND mmc.is_active = true
                                WHERE msc.service_def_id = sdc.id AND msc.is_active = true)
                    AND sdc.category = CAST(:category AS text)))
              AND (CAST(:q AS text) IS NULL
                   OR s.name ILIKE CAST(:q AS text)
                   OR EXISTS (SELECT 1
                              FROM service_definitions sdq
                              WHERE sdq.owner_type = 'SALON'
                                AND sdq.owner_id = s.id
                                AND sdq.is_active = true
                                AND EXISTS (SELECT 1 FROM master_services msq
                                            JOIN masters mmq ON mmq.id = msq.master_id AND mmq.is_active = true
                                            WHERE msq.service_def_id = sdq.id AND msq.is_active = true)
                                AND sdq.name ILIKE CAST(:q AS text)))
            """,
            nativeQuery = true)
    Page<SalonSearchProjection> findActiveByCityIdNoPriceAsProjection(
            @Param("cityId") UUID cityId,
            @Param("category") String category,
            @Param("q") String q,
            Pageable pageable
    );

    /**
     * All active salons (no-locality-filter branch of the Phase 10.5 FK
     * location filter — both cityId and districtId resolved to {@code null}).
     *
     * <p><b>Plan shape (Phase 10.8, MEDIUM-1):</b> a single constant predicate
     * {@code is_active = true} with no locality column reference — no
     * non-SARGable OR-chain. Spring Data derives the matching {@code COUNT(*)}
     * companion. {@link SearchService#searchSalons} dispatches here only when
     * no locality filter was supplied.
     *
     * <p><b>§E — search path MUST use the projection overload.</b>
     * {@link SearchService} must call
     * {@link #findByIsActiveTrueAsProjection(String, java.math.BigDecimal, java.math.BigDecimal, String, Pageable)}.
     * This full-entity overload remains for non-search callers.
     */
    Page<Salon> findByIsActiveTrue(Pageable pageable);

    /**
     * Projection overload of {@link #findByIsActiveTrue}: fetches only the
     * columns needed for salon discovery plus the bookable price band and
     * category-scoped {@code serviceNames} preview.
     *
     * <p>Used exclusively by {@link SearchService#findSalonsByLocation}.
     */
    @Query(value = """
            SELECT t.id           AS id,
                   t.name         AS name,
                   t.city_id      AS city_id,
                   t.district_id  AS district_id,
                   t.avatar_url   AS avatar_url,
                   t.price_min    AS price_min,
                   t.price_max    AS price_max,
                   pn.pnames      AS service_names,
                   t.street       AS street,
                   t.building_no  AS building_no,
                   t.location_note AS location_note
            FROM (
                SELECT s.id           AS id,
                       s.name         AS name,
                       s.city_id      AS city_id,
                       s.district_id  AS district_id,
                       s.avatar_url   AS avatar_url,
                       pr.pmin        AS price_min,
                       pr.pmax        AS price_max,
                       s.street       AS street,
                       s.building_no  AS building_no,
                       s.location_note AS location_note
                FROM salons s
                LEFT JOIN LATERAL (
                    SELECT MIN(COALESCE(ms.price_override, sd.base_price)) AS pmin,
                           MAX(COALESCE(ms.price_override,
                                        CASE WHEN sd.price_type = 'RANGE'
                                             THEN sd.price_max ELSE sd.base_price END)) AS pmax
                    FROM master_services ms
                    JOIN service_definitions sd ON sd.id = ms.service_def_id AND sd.is_active = true
                    JOIN masters mad ON mad.id = ms.master_id AND mad.is_active = true
                    WHERE sd.owner_type = 'SALON'
                      AND sd.owner_id = s.id
                      AND ms.is_active = true
                      AND (CAST(:category AS text) IS NULL OR sd.category = CAST(:category AS text))
                ) pr ON true
                WHERE s.is_active = true
                  AND (CAST(:category AS text) IS NULL OR EXISTS (
                      SELECT 1 FROM service_definitions sdc
                      WHERE sdc.owner_type = 'SALON'
                        AND sdc.owner_id = s.id
                        AND sdc.is_active = true
                        AND EXISTS (SELECT 1 FROM master_services msc
                                    JOIN masters mmc ON mmc.id = msc.master_id AND mmc.is_active = true
                                    WHERE msc.service_def_id = sdc.id AND msc.is_active = true)
                        AND sdc.category = CAST(:category AS text)))
                  AND (CAST(:q AS text) IS NULL
                       OR s.name ILIKE CAST(:q AS text)
                       OR EXISTS (SELECT 1
                                  FROM service_definitions sdq
                                  WHERE sdq.owner_type = 'SALON'
                                    AND sdq.owner_id = s.id
                                    AND sdq.is_active = true
                                    AND EXISTS (SELECT 1 FROM master_services msq
                                                JOIN masters mmq ON mmq.id = msq.master_id AND mmq.is_active = true
                                                WHERE msq.service_def_id = sdq.id AND msq.is_active = true)
                                    AND sdq.name ILIKE CAST(:q AS text)))
                  AND (CAST(:minPrice AS numeric) IS NULL OR pr.pmax >= CAST(:minPrice AS numeric))
                  AND (CAST(:maxPrice AS numeric) IS NULL OR pr.pmin <= CAST(:maxPrice AS numeric))
            ) t
            LEFT JOIN LATERAL (
                SELECT array_agg(z.name) AS pnames
                  FROM (SELECT DISTINCT sd2.name AS name
                        FROM service_definitions sd2
                        WHERE sd2.owner_type = 'SALON'
                          AND sd2.owner_id = t.id
                          AND sd2.is_active = true
                          AND EXISTS (SELECT 1 FROM master_services ms2
                                      JOIN masters mm2 ON mm2.id = ms2.master_id AND mm2.is_active = true
                                      WHERE ms2.service_def_id = sd2.id AND ms2.is_active = true)
                          AND (CAST(:category AS text) IS NULL OR sd2.category = CAST(:category AS text))
                        ORDER BY sd2.name
                        LIMIT 3) z) pn ON true
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM salons s
            LEFT JOIN LATERAL (
                SELECT MIN(COALESCE(ms.price_override, sd.base_price)) AS pmin,
                       MAX(COALESCE(ms.price_override,
                                    CASE WHEN sd.price_type = 'RANGE'
                                         THEN sd.price_max ELSE sd.base_price END)) AS pmax
                FROM master_services ms
                JOIN service_definitions sd ON sd.id = ms.service_def_id AND sd.is_active = true
                JOIN masters mad ON mad.id = ms.master_id AND mad.is_active = true
                WHERE sd.owner_type = 'SALON'
                  AND sd.owner_id = s.id
                  AND ms.is_active = true
                  AND (CAST(:category AS text) IS NULL OR sd.category = CAST(:category AS text))
            ) pr ON true
            WHERE s.is_active = true
              AND (CAST(:category AS text) IS NULL OR EXISTS (
                  SELECT 1 FROM service_definitions sdc
                  WHERE sdc.owner_type = 'SALON'
                    AND sdc.owner_id = s.id
                    AND sdc.is_active = true
                    AND EXISTS (SELECT 1 FROM master_services msc
                                JOIN masters mmc ON mmc.id = msc.master_id AND mmc.is_active = true
                                WHERE msc.service_def_id = sdc.id AND msc.is_active = true)
                    AND sdc.category = CAST(:category AS text)))
              AND (CAST(:q AS text) IS NULL
                   OR s.name ILIKE CAST(:q AS text)
                   OR EXISTS (SELECT 1
                              FROM service_definitions sdq
                              WHERE sdq.owner_type = 'SALON'
                                AND sdq.owner_id = s.id
                                AND sdq.is_active = true
                                AND EXISTS (SELECT 1 FROM master_services msq
                                            JOIN masters mmq ON mmq.id = msq.master_id AND mmq.is_active = true
                                            WHERE msq.service_def_id = sdq.id AND msq.is_active = true)
                                AND sdq.name ILIKE CAST(:q AS text)))
              AND (CAST(:minPrice AS numeric) IS NULL OR pr.pmax >= CAST(:minPrice AS numeric))
              AND (CAST(:maxPrice AS numeric) IS NULL OR pr.pmin <= CAST(:maxPrice AS numeric))
            """,
            nativeQuery = true)
    Page<SalonSearchProjection> findByIsActiveTrueAsProjection(
            @Param("q") String q,
            @Param("minPrice") java.math.BigDecimal minPrice,
            @Param("maxPrice") java.math.BigDecimal maxPrice,
            @Param("category") String category,
            Pageable pageable
    );

    /**
     * No-price-bound variant of {@link #findByIsActiveTrueAsProjection}
     * (HIGH PERF gate). Plain {@code COUNT(*)} count query (no LATERAL) for the
     * common no-price-filter path; the data query keeps the LATERAL because the
     * price band is a display column. {@link SearchService} dispatches here when
     * both price bounds are null and no locality filter was supplied.
     */
    @Query(value = """
            SELECT t.id           AS id,
                   t.name         AS name,
                   t.city_id      AS city_id,
                   t.district_id  AS district_id,
                   t.avatar_url   AS avatar_url,
                   t.price_min    AS price_min,
                   t.price_max    AS price_max,
                   pn.pnames      AS service_names,
                   t.street       AS street,
                   t.building_no  AS building_no,
                   t.location_note AS location_note
            FROM (
                SELECT s.id           AS id,
                       s.name         AS name,
                       s.city_id      AS city_id,
                       s.district_id  AS district_id,
                       s.avatar_url   AS avatar_url,
                       pr.pmin        AS price_min,
                       pr.pmax        AS price_max,
                       s.street       AS street,
                       s.building_no  AS building_no,
                       s.location_note AS location_note
                FROM salons s
                LEFT JOIN LATERAL (
                    SELECT MIN(COALESCE(ms.price_override, sd.base_price)) AS pmin,
                           MAX(COALESCE(ms.price_override,
                                        CASE WHEN sd.price_type = 'RANGE'
                                             THEN sd.price_max ELSE sd.base_price END)) AS pmax
                    FROM master_services ms
                    JOIN service_definitions sd ON sd.id = ms.service_def_id AND sd.is_active = true
                    JOIN masters mad ON mad.id = ms.master_id AND mad.is_active = true
                    WHERE sd.owner_type = 'SALON'
                      AND sd.owner_id = s.id
                      AND ms.is_active = true
                      AND (CAST(:category AS text) IS NULL OR sd.category = CAST(:category AS text))
                ) pr ON true
                WHERE s.is_active = true
                  AND (CAST(:category AS text) IS NULL OR EXISTS (
                      SELECT 1 FROM service_definitions sdc
                      WHERE sdc.owner_type = 'SALON'
                        AND sdc.owner_id = s.id
                        AND sdc.is_active = true
                        AND EXISTS (SELECT 1 FROM master_services msc
                                    JOIN masters mmc ON mmc.id = msc.master_id AND mmc.is_active = true
                                    WHERE msc.service_def_id = sdc.id AND msc.is_active = true)
                        AND sdc.category = CAST(:category AS text)))
                  AND (CAST(:q AS text) IS NULL
                       OR s.name ILIKE CAST(:q AS text)
                       OR EXISTS (SELECT 1
                                  FROM service_definitions sdq
                                  WHERE sdq.owner_type = 'SALON'
                                    AND sdq.owner_id = s.id
                                    AND sdq.is_active = true
                                    AND EXISTS (SELECT 1 FROM master_services msq
                                                JOIN masters mmq ON mmq.id = msq.master_id AND mmq.is_active = true
                                                WHERE msq.service_def_id = sdq.id AND msq.is_active = true)
                                    AND sdq.name ILIKE CAST(:q AS text)))
            ) t
            LEFT JOIN LATERAL (
                SELECT array_agg(z.name) AS pnames
                  FROM (SELECT DISTINCT sd2.name AS name
                        FROM service_definitions sd2
                        WHERE sd2.owner_type = 'SALON'
                          AND sd2.owner_id = t.id
                          AND sd2.is_active = true
                          AND EXISTS (SELECT 1 FROM master_services ms2
                                      JOIN masters mm2 ON mm2.id = ms2.master_id AND mm2.is_active = true
                                      WHERE ms2.service_def_id = sd2.id AND ms2.is_active = true)
                          AND (CAST(:category AS text) IS NULL OR sd2.category = CAST(:category AS text))
                        ORDER BY sd2.name
                        LIMIT 3) z) pn ON true
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM salons s
            WHERE s.is_active = true
              AND (CAST(:category AS text) IS NULL OR EXISTS (
                  SELECT 1 FROM service_definitions sdc
                  WHERE sdc.owner_type = 'SALON'
                    AND sdc.owner_id = s.id
                    AND sdc.is_active = true
                    AND EXISTS (SELECT 1 FROM master_services msc
                                JOIN masters mmc ON mmc.id = msc.master_id AND mmc.is_active = true
                                WHERE msc.service_def_id = sdc.id AND msc.is_active = true)
                    AND sdc.category = CAST(:category AS text)))
              AND (CAST(:q AS text) IS NULL
                   OR s.name ILIKE CAST(:q AS text)
                   OR EXISTS (SELECT 1
                              FROM service_definitions sdq
                              WHERE sdq.owner_type = 'SALON'
                                AND sdq.owner_id = s.id
                                AND sdq.is_active = true
                                AND EXISTS (SELECT 1 FROM master_services msq
                                            JOIN masters mmq ON mmq.id = msq.master_id AND mmq.is_active = true
                                            WHERE msq.service_def_id = sdq.id AND msq.is_active = true)
                                AND sdq.name ILIKE CAST(:q AS text)))
            """,
            nativeQuery = true)
    Page<SalonSearchProjection> findByIsActiveTrueNoPriceAsProjection(
            @Param("q") String q,
            @Param("category") String category,
            Pageable pageable
    );
}
