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
     * five columns needed for salon discovery
     * ({@code id, name, city_id, district_id, avatar_url}) — no full entity
     * hydration, no lazy proxy for the {@code owner} association.
     *
     * <p>Used exclusively by {@link SearchService#findSalonsByLocation} to
     * eliminate the LOW PERF finding ("Loads Salon entity then maps via
     * Page#map"). Hibernate translates the {@link SalonSearchProjection}
     * return type into a narrow {@code SELECT} containing only those columns.
     *
     * @param districtId resolved discovery district id (never {@code null})
     */
    @Query(value = """
            SELECT s.id           AS id,
                   s.name         AS name,
                   s.city_id      AS city_id,
                   s.district_id  AS district_id,
                   s.avatar_url   AS avatar_url,
                   pr.pmin        AS price_min,
                   pr.pmax        AS price_max,
                   pr.pnames      AS service_names,
                   s.street       AS street,
                   s.building_no  AS building_no,
                   s.location_note AS location_note
            FROM salons s
            LEFT JOIN LATERAL (
                SELECT MIN(sd.base_price) AS pmin,
                       MAX(CASE WHEN sd.price_type = 'RANGE'
                                THEN sd.price_max ELSE sd.base_price END) AS pmax,
                       (SELECT array_agg(z.name)
                          FROM (SELECT DISTINCT sd2.name AS name
                                FROM master_services ms2
                                JOIN masters mm2 ON mm2.id = ms2.master_id
                                JOIN service_definitions sd2 ON sd2.id = ms2.service_def_id
                                WHERE mm2.salon_id = s.id
                                  AND mm2.is_active = true
                                  AND ms2.is_active = true
                                  AND sd2.is_active = true
                                  AND (CAST(:category AS text) IS NULL OR sd2.category = CAST(:category AS text))
                                ORDER BY sd2.name
                                LIMIT 3) z) AS pnames
                FROM master_services ms
                JOIN masters mm ON mm.id = ms.master_id
                JOIN service_definitions sd ON sd.id = ms.service_def_id
                WHERE mm.salon_id = s.id
                  AND mm.is_active = true
                  AND ms.is_active = true
                  AND sd.is_active = true
                  AND (CAST(:category AS text) IS NULL OR sd.category = CAST(:category AS text))
            ) pr ON true
            WHERE s.is_active = true
              AND s.district_id = :districtId
              AND (CAST(:category AS text) IS NULL OR EXISTS (
                  SELECT 1 FROM master_services msc
                  JOIN masters mmc ON mmc.id = msc.master_id
                  JOIN service_definitions sdc ON sdc.id = msc.service_def_id
                  WHERE mmc.salon_id = s.id
                    AND mmc.is_active = true
                    AND msc.is_active = true
                    AND sdc.is_active = true
                    AND sdc.category = CAST(:category AS text)))
              AND (CAST(:q AS text) IS NULL
                   OR s.name ILIKE CAST(:q AS text)
                   OR EXISTS (SELECT 1
                              FROM master_services msq
                              JOIN masters mmq ON mmq.id = msq.master_id
                              JOIN service_definitions sdq ON sdq.id = msq.service_def_id
                              WHERE mmq.salon_id = s.id
                                AND mmq.is_active = true
                                AND msq.is_active = true
                                AND sdq.is_active = true
                                AND sdq.name ILIKE CAST(:q AS text)))
              AND (CAST(:minPrice AS numeric) IS NULL OR pr.pmax >= CAST(:minPrice AS numeric))
              AND (CAST(:maxPrice AS numeric) IS NULL OR pr.pmin <= CAST(:maxPrice AS numeric))
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM salons s
            LEFT JOIN LATERAL (
                SELECT MIN(sd.base_price) AS pmin,
                       MAX(CASE WHEN sd.price_type = 'RANGE'
                                THEN sd.price_max ELSE sd.base_price END) AS pmax
                FROM master_services ms
                JOIN masters mm ON mm.id = ms.master_id
                JOIN service_definitions sd ON sd.id = ms.service_def_id
                WHERE mm.salon_id = s.id
                  AND mm.is_active = true
                  AND ms.is_active = true
                  AND sd.is_active = true
                  AND (CAST(:category AS text) IS NULL OR sd.category = CAST(:category AS text))
            ) pr ON true
            WHERE s.is_active = true
              AND s.district_id = :districtId
              AND (CAST(:category AS text) IS NULL OR EXISTS (
                  SELECT 1 FROM master_services msc
                  JOIN masters mmc ON mmc.id = msc.master_id
                  JOIN service_definitions sdc ON sdc.id = msc.service_def_id
                  WHERE mmc.salon_id = s.id
                    AND mmc.is_active = true
                    AND msc.is_active = true
                    AND sdc.is_active = true
                    AND sdc.category = CAST(:category AS text)))
              AND (CAST(:q AS text) IS NULL
                   OR s.name ILIKE CAST(:q AS text)
                   OR EXISTS (SELECT 1
                              FROM master_services msq
                              JOIN masters mmq ON mmq.id = msq.master_id
                              JOIN service_definitions sdq ON sdq.id = msq.service_def_id
                              WHERE mmq.salon_id = s.id
                                AND mmq.is_active = true
                                AND msq.is_active = true
                                AND sdq.is_active = true
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
            SELECT s.id           AS id,
                   s.name         AS name,
                   s.city_id      AS city_id,
                   s.district_id  AS district_id,
                   s.avatar_url   AS avatar_url,
                   pr.pmin        AS price_min,
                   pr.pmax        AS price_max,
                   pr.pnames      AS service_names,
                   s.street       AS street,
                   s.building_no  AS building_no,
                   s.location_note AS location_note
            FROM salons s
            LEFT JOIN LATERAL (
                SELECT MIN(sd.base_price) AS pmin,
                       MAX(CASE WHEN sd.price_type = 'RANGE'
                                THEN sd.price_max ELSE sd.base_price END) AS pmax,
                       (SELECT array_agg(z.name)
                          FROM (SELECT DISTINCT sd2.name AS name
                                FROM master_services ms2
                                JOIN masters mm2 ON mm2.id = ms2.master_id
                                JOIN service_definitions sd2 ON sd2.id = ms2.service_def_id
                                WHERE mm2.salon_id = s.id
                                  AND mm2.is_active = true
                                  AND ms2.is_active = true
                                  AND sd2.is_active = true
                                  AND (CAST(:category AS text) IS NULL OR sd2.category = CAST(:category AS text))
                                ORDER BY sd2.name
                                LIMIT 3) z) AS pnames
                FROM master_services ms
                JOIN masters mm ON mm.id = ms.master_id
                JOIN service_definitions sd ON sd.id = ms.service_def_id
                WHERE mm.salon_id = s.id
                  AND mm.is_active = true
                  AND ms.is_active = true
                  AND sd.is_active = true
                  AND (CAST(:category AS text) IS NULL OR sd.category = CAST(:category AS text))
            ) pr ON true
            WHERE s.is_active = true
              AND s.district_id = :districtId
              AND (CAST(:category AS text) IS NULL OR EXISTS (
                  SELECT 1 FROM master_services msc
                  JOIN masters mmc ON mmc.id = msc.master_id
                  JOIN service_definitions sdc ON sdc.id = msc.service_def_id
                  WHERE mmc.salon_id = s.id
                    AND mmc.is_active = true
                    AND msc.is_active = true
                    AND sdc.is_active = true
                    AND sdc.category = CAST(:category AS text)))
              AND (CAST(:q AS text) IS NULL
                   OR s.name ILIKE CAST(:q AS text)
                   OR EXISTS (SELECT 1
                              FROM master_services msq
                              JOIN masters mmq ON mmq.id = msq.master_id
                              JOIN service_definitions sdq ON sdq.id = msq.service_def_id
                              WHERE mmq.salon_id = s.id
                                AND mmq.is_active = true
                                AND msq.is_active = true
                                AND sdq.is_active = true
                                AND sdq.name ILIKE CAST(:q AS text)))
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM salons s
            WHERE s.is_active = true
              AND s.district_id = :districtId
              AND (CAST(:category AS text) IS NULL OR EXISTS (
                  SELECT 1 FROM master_services msc
                  JOIN masters mmc ON mmc.id = msc.master_id
                  JOIN service_definitions sdc ON sdc.id = msc.service_def_id
                  WHERE mmc.salon_id = s.id
                    AND mmc.is_active = true
                    AND msc.is_active = true
                    AND sdc.is_active = true
                    AND sdc.category = CAST(:category AS text)))
              AND (CAST(:q AS text) IS NULL
                   OR s.name ILIKE CAST(:q AS text)
                   OR EXISTS (SELECT 1
                              FROM master_services msq
                              JOIN masters mmq ON mmq.id = msq.master_id
                              JOIN service_definitions sdq ON sdq.id = msq.service_def_id
                              WHERE mmq.salon_id = s.id
                                AND mmq.is_active = true
                                AND msq.is_active = true
                                AND sdq.is_active = true
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
     * five columns needed for salon discovery
     * ({@code id, name, city_id, district_id, avatar_url}).
     *
     * <p>Used exclusively by {@link SearchService#findSalonsByLocation}.
     *
     * @param cityId resolved discovery city id (never {@code null})
     */
    @Query(value = """
            SELECT s.id           AS id,
                   s.name         AS name,
                   s.city_id      AS city_id,
                   s.district_id  AS district_id,
                   s.avatar_url   AS avatar_url,
                   pr.pmin        AS price_min,
                   pr.pmax        AS price_max,
                   pr.pnames      AS service_names,
                   s.street       AS street,
                   s.building_no  AS building_no,
                   s.location_note AS location_note
            FROM salons s
            LEFT JOIN LATERAL (
                SELECT MIN(sd.base_price) AS pmin,
                       MAX(CASE WHEN sd.price_type = 'RANGE'
                                THEN sd.price_max ELSE sd.base_price END) AS pmax,
                       (SELECT array_agg(z.name)
                          FROM (SELECT DISTINCT sd2.name AS name
                                FROM master_services ms2
                                JOIN masters mm2 ON mm2.id = ms2.master_id
                                JOIN service_definitions sd2 ON sd2.id = ms2.service_def_id
                                WHERE mm2.salon_id = s.id
                                  AND mm2.is_active = true
                                  AND ms2.is_active = true
                                  AND sd2.is_active = true
                                  AND (CAST(:category AS text) IS NULL OR sd2.category = CAST(:category AS text))
                                ORDER BY sd2.name
                                LIMIT 3) z) AS pnames
                FROM master_services ms
                JOIN masters mm ON mm.id = ms.master_id
                JOIN service_definitions sd ON sd.id = ms.service_def_id
                WHERE mm.salon_id = s.id
                  AND mm.is_active = true
                  AND ms.is_active = true
                  AND sd.is_active = true
                  AND (CAST(:category AS text) IS NULL OR sd.category = CAST(:category AS text))
            ) pr ON true
            WHERE s.is_active = true
              AND s.city_id = :cityId
              AND (CAST(:category AS text) IS NULL OR EXISTS (
                  SELECT 1 FROM master_services msc
                  JOIN masters mmc ON mmc.id = msc.master_id
                  JOIN service_definitions sdc ON sdc.id = msc.service_def_id
                  WHERE mmc.salon_id = s.id
                    AND mmc.is_active = true
                    AND msc.is_active = true
                    AND sdc.is_active = true
                    AND sdc.category = CAST(:category AS text)))
              AND (CAST(:q AS text) IS NULL
                   OR s.name ILIKE CAST(:q AS text)
                   OR EXISTS (SELECT 1
                              FROM master_services msq
                              JOIN masters mmq ON mmq.id = msq.master_id
                              JOIN service_definitions sdq ON sdq.id = msq.service_def_id
                              WHERE mmq.salon_id = s.id
                                AND mmq.is_active = true
                                AND msq.is_active = true
                                AND sdq.is_active = true
                                AND sdq.name ILIKE CAST(:q AS text)))
              AND (CAST(:minPrice AS numeric) IS NULL OR pr.pmax >= CAST(:minPrice AS numeric))
              AND (CAST(:maxPrice AS numeric) IS NULL OR pr.pmin <= CAST(:maxPrice AS numeric))
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM salons s
            LEFT JOIN LATERAL (
                SELECT MIN(sd.base_price) AS pmin,
                       MAX(CASE WHEN sd.price_type = 'RANGE'
                                THEN sd.price_max ELSE sd.base_price END) AS pmax
                FROM master_services ms
                JOIN masters mm ON mm.id = ms.master_id
                JOIN service_definitions sd ON sd.id = ms.service_def_id
                WHERE mm.salon_id = s.id
                  AND mm.is_active = true
                  AND ms.is_active = true
                  AND sd.is_active = true
                  AND (CAST(:category AS text) IS NULL OR sd.category = CAST(:category AS text))
            ) pr ON true
            WHERE s.is_active = true
              AND s.city_id = :cityId
              AND (CAST(:category AS text) IS NULL OR EXISTS (
                  SELECT 1 FROM master_services msc
                  JOIN masters mmc ON mmc.id = msc.master_id
                  JOIN service_definitions sdc ON sdc.id = msc.service_def_id
                  WHERE mmc.salon_id = s.id
                    AND mmc.is_active = true
                    AND msc.is_active = true
                    AND sdc.is_active = true
                    AND sdc.category = CAST(:category AS text)))
              AND (CAST(:q AS text) IS NULL
                   OR s.name ILIKE CAST(:q AS text)
                   OR EXISTS (SELECT 1
                              FROM master_services msq
                              JOIN masters mmq ON mmq.id = msq.master_id
                              JOIN service_definitions sdq ON sdq.id = msq.service_def_id
                              WHERE mmq.salon_id = s.id
                                AND mmq.is_active = true
                                AND msq.is_active = true
                                AND sdq.is_active = true
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
            SELECT s.id           AS id,
                   s.name         AS name,
                   s.city_id      AS city_id,
                   s.district_id  AS district_id,
                   s.avatar_url   AS avatar_url,
                   pr.pmin        AS price_min,
                   pr.pmax        AS price_max,
                   pr.pnames      AS service_names,
                   s.street       AS street,
                   s.building_no  AS building_no,
                   s.location_note AS location_note
            FROM salons s
            LEFT JOIN LATERAL (
                SELECT MIN(sd.base_price) AS pmin,
                       MAX(CASE WHEN sd.price_type = 'RANGE'
                                THEN sd.price_max ELSE sd.base_price END) AS pmax,
                       (SELECT array_agg(z.name)
                          FROM (SELECT DISTINCT sd2.name AS name
                                FROM master_services ms2
                                JOIN masters mm2 ON mm2.id = ms2.master_id
                                JOIN service_definitions sd2 ON sd2.id = ms2.service_def_id
                                WHERE mm2.salon_id = s.id
                                  AND mm2.is_active = true
                                  AND ms2.is_active = true
                                  AND sd2.is_active = true
                                  AND (CAST(:category AS text) IS NULL OR sd2.category = CAST(:category AS text))
                                ORDER BY sd2.name
                                LIMIT 3) z) AS pnames
                FROM master_services ms
                JOIN masters mm ON mm.id = ms.master_id
                JOIN service_definitions sd ON sd.id = ms.service_def_id
                WHERE mm.salon_id = s.id
                  AND mm.is_active = true
                  AND ms.is_active = true
                  AND sd.is_active = true
                  AND (CAST(:category AS text) IS NULL OR sd.category = CAST(:category AS text))
            ) pr ON true
            WHERE s.is_active = true
              AND s.city_id = :cityId
              AND (CAST(:category AS text) IS NULL OR EXISTS (
                  SELECT 1 FROM master_services msc
                  JOIN masters mmc ON mmc.id = msc.master_id
                  JOIN service_definitions sdc ON sdc.id = msc.service_def_id
                  WHERE mmc.salon_id = s.id
                    AND mmc.is_active = true
                    AND msc.is_active = true
                    AND sdc.is_active = true
                    AND sdc.category = CAST(:category AS text)))
              AND (CAST(:q AS text) IS NULL
                   OR s.name ILIKE CAST(:q AS text)
                   OR EXISTS (SELECT 1
                              FROM master_services msq
                              JOIN masters mmq ON mmq.id = msq.master_id
                              JOIN service_definitions sdq ON sdq.id = msq.service_def_id
                              WHERE mmq.salon_id = s.id
                                AND mmq.is_active = true
                                AND msq.is_active = true
                                AND sdq.is_active = true
                                AND sdq.name ILIKE CAST(:q AS text)))
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM salons s
            WHERE s.is_active = true
              AND s.city_id = :cityId
              AND (CAST(:category AS text) IS NULL OR EXISTS (
                  SELECT 1 FROM master_services msc
                  JOIN masters mmc ON mmc.id = msc.master_id
                  JOIN service_definitions sdc ON sdc.id = msc.service_def_id
                  WHERE mmc.salon_id = s.id
                    AND mmc.is_active = true
                    AND msc.is_active = true
                    AND sdc.is_active = true
                    AND sdc.category = CAST(:category AS text)))
              AND (CAST(:q AS text) IS NULL
                   OR s.name ILIKE CAST(:q AS text)
                   OR EXISTS (SELECT 1
                              FROM master_services msq
                              JOIN masters mmq ON mmq.id = msq.master_id
                              JOIN service_definitions sdq ON sdq.id = msq.service_def_id
                              WHERE mmq.salon_id = s.id
                                AND mmq.is_active = true
                                AND msq.is_active = true
                                AND sdq.is_active = true
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
     * five columns needed for salon discovery
     * ({@code id, name, city_id, district_id, avatar_url}).
     *
     * <p>Used exclusively by {@link SearchService#findSalonsByLocation}.
     */
    @Query(value = """
            SELECT s.id           AS id,
                   s.name         AS name,
                   s.city_id      AS city_id,
                   s.district_id  AS district_id,
                   s.avatar_url   AS avatar_url,
                   pr.pmin        AS price_min,
                   pr.pmax        AS price_max,
                   pr.pnames      AS service_names,
                   s.street       AS street,
                   s.building_no  AS building_no,
                   s.location_note AS location_note
            FROM salons s
            LEFT JOIN LATERAL (
                SELECT MIN(sd.base_price) AS pmin,
                       MAX(CASE WHEN sd.price_type = 'RANGE'
                                THEN sd.price_max ELSE sd.base_price END) AS pmax,
                       (SELECT array_agg(z.name)
                          FROM (SELECT DISTINCT sd2.name AS name
                                FROM master_services ms2
                                JOIN masters mm2 ON mm2.id = ms2.master_id
                                JOIN service_definitions sd2 ON sd2.id = ms2.service_def_id
                                WHERE mm2.salon_id = s.id
                                  AND mm2.is_active = true
                                  AND ms2.is_active = true
                                  AND sd2.is_active = true
                                  AND (CAST(:category AS text) IS NULL OR sd2.category = CAST(:category AS text))
                                ORDER BY sd2.name
                                LIMIT 3) z) AS pnames
                FROM master_services ms
                JOIN masters mm ON mm.id = ms.master_id
                JOIN service_definitions sd ON sd.id = ms.service_def_id
                WHERE mm.salon_id = s.id
                  AND mm.is_active = true
                  AND ms.is_active = true
                  AND sd.is_active = true
                  AND (CAST(:category AS text) IS NULL OR sd.category = CAST(:category AS text))
            ) pr ON true
            WHERE s.is_active = true
              AND (CAST(:category AS text) IS NULL OR EXISTS (
                  SELECT 1 FROM master_services msc
                  JOIN masters mmc ON mmc.id = msc.master_id
                  JOIN service_definitions sdc ON sdc.id = msc.service_def_id
                  WHERE mmc.salon_id = s.id
                    AND mmc.is_active = true
                    AND msc.is_active = true
                    AND sdc.is_active = true
                    AND sdc.category = CAST(:category AS text)))
              AND (CAST(:q AS text) IS NULL
                   OR s.name ILIKE CAST(:q AS text)
                   OR EXISTS (SELECT 1
                              FROM master_services msq
                              JOIN masters mmq ON mmq.id = msq.master_id
                              JOIN service_definitions sdq ON sdq.id = msq.service_def_id
                              WHERE mmq.salon_id = s.id
                                AND mmq.is_active = true
                                AND msq.is_active = true
                                AND sdq.is_active = true
                                AND sdq.name ILIKE CAST(:q AS text)))
              AND (CAST(:minPrice AS numeric) IS NULL OR pr.pmax >= CAST(:minPrice AS numeric))
              AND (CAST(:maxPrice AS numeric) IS NULL OR pr.pmin <= CAST(:maxPrice AS numeric))
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM salons s
            LEFT JOIN LATERAL (
                SELECT MIN(sd.base_price) AS pmin,
                       MAX(CASE WHEN sd.price_type = 'RANGE'
                                THEN sd.price_max ELSE sd.base_price END) AS pmax
                FROM master_services ms
                JOIN masters mm ON mm.id = ms.master_id
                JOIN service_definitions sd ON sd.id = ms.service_def_id
                WHERE mm.salon_id = s.id
                  AND mm.is_active = true
                  AND ms.is_active = true
                  AND sd.is_active = true
                  AND (CAST(:category AS text) IS NULL OR sd.category = CAST(:category AS text))
            ) pr ON true
            WHERE s.is_active = true
              AND (CAST(:category AS text) IS NULL OR EXISTS (
                  SELECT 1 FROM master_services msc
                  JOIN masters mmc ON mmc.id = msc.master_id
                  JOIN service_definitions sdc ON sdc.id = msc.service_def_id
                  WHERE mmc.salon_id = s.id
                    AND mmc.is_active = true
                    AND msc.is_active = true
                    AND sdc.is_active = true
                    AND sdc.category = CAST(:category AS text)))
              AND (CAST(:q AS text) IS NULL
                   OR s.name ILIKE CAST(:q AS text)
                   OR EXISTS (SELECT 1
                              FROM master_services msq
                              JOIN masters mmq ON mmq.id = msq.master_id
                              JOIN service_definitions sdq ON sdq.id = msq.service_def_id
                              WHERE mmq.salon_id = s.id
                                AND mmq.is_active = true
                                AND msq.is_active = true
                                AND sdq.is_active = true
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
            SELECT s.id           AS id,
                   s.name         AS name,
                   s.city_id      AS city_id,
                   s.district_id  AS district_id,
                   s.avatar_url   AS avatar_url,
                   pr.pmin        AS price_min,
                   pr.pmax        AS price_max,
                   pr.pnames      AS service_names,
                   s.street       AS street,
                   s.building_no  AS building_no,
                   s.location_note AS location_note
            FROM salons s
            LEFT JOIN LATERAL (
                SELECT MIN(sd.base_price) AS pmin,
                       MAX(CASE WHEN sd.price_type = 'RANGE'
                                THEN sd.price_max ELSE sd.base_price END) AS pmax,
                       (SELECT array_agg(z.name)
                          FROM (SELECT DISTINCT sd2.name AS name
                                FROM master_services ms2
                                JOIN masters mm2 ON mm2.id = ms2.master_id
                                JOIN service_definitions sd2 ON sd2.id = ms2.service_def_id
                                WHERE mm2.salon_id = s.id
                                  AND mm2.is_active = true
                                  AND ms2.is_active = true
                                  AND sd2.is_active = true
                                  AND (CAST(:category AS text) IS NULL OR sd2.category = CAST(:category AS text))
                                ORDER BY sd2.name
                                LIMIT 3) z) AS pnames
                FROM master_services ms
                JOIN masters mm ON mm.id = ms.master_id
                JOIN service_definitions sd ON sd.id = ms.service_def_id
                WHERE mm.salon_id = s.id
                  AND mm.is_active = true
                  AND ms.is_active = true
                  AND sd.is_active = true
                  AND (CAST(:category AS text) IS NULL OR sd.category = CAST(:category AS text))
            ) pr ON true
            WHERE s.is_active = true
              AND (CAST(:category AS text) IS NULL OR EXISTS (
                  SELECT 1 FROM master_services msc
                  JOIN masters mmc ON mmc.id = msc.master_id
                  JOIN service_definitions sdc ON sdc.id = msc.service_def_id
                  WHERE mmc.salon_id = s.id
                    AND mmc.is_active = true
                    AND msc.is_active = true
                    AND sdc.is_active = true
                    AND sdc.category = CAST(:category AS text)))
              AND (CAST(:q AS text) IS NULL
                   OR s.name ILIKE CAST(:q AS text)
                   OR EXISTS (SELECT 1
                              FROM master_services msq
                              JOIN masters mmq ON mmq.id = msq.master_id
                              JOIN service_definitions sdq ON sdq.id = msq.service_def_id
                              WHERE mmq.salon_id = s.id
                                AND mmq.is_active = true
                                AND msq.is_active = true
                                AND sdq.is_active = true
                                AND sdq.name ILIKE CAST(:q AS text)))
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM salons s
            WHERE s.is_active = true
              AND (CAST(:category AS text) IS NULL OR EXISTS (
                  SELECT 1 FROM master_services msc
                  JOIN masters mmc ON mmc.id = msc.master_id
                  JOIN service_definitions sdc ON sdc.id = msc.service_def_id
                  WHERE mmc.salon_id = s.id
                    AND mmc.is_active = true
                    AND msc.is_active = true
                    AND sdc.is_active = true
                    AND sdc.category = CAST(:category AS text)))
              AND (CAST(:q AS text) IS NULL
                   OR s.name ILIKE CAST(:q AS text)
                   OR EXISTS (SELECT 1
                              FROM master_services msq
                              JOIN masters mmq ON mmq.id = msq.master_id
                              JOIN service_definitions sdq ON sdq.id = msq.service_def_id
                              WHERE mmq.salon_id = s.id
                                AND mmq.is_active = true
                                AND msq.is_active = true
                                AND sdq.is_active = true
                                AND sdq.name ILIKE CAST(:q AS text)))
            """,
            nativeQuery = true)
    Page<SalonSearchProjection> findByIsActiveTrueNoPriceAsProjection(
            @Param("q") String q,
            @Param("category") String category,
            Pageable pageable
    );
}
