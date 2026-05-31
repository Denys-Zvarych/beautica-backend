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
     * {@link #findActiveByDistrictIdAsProjection(UUID, Pageable)} so only the
     * five columns needed by {@link SalonSearchProjection} are fetched.
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
    @Query("""
            SELECT s.id AS id,
                   s.name AS name,
                   s.cityId AS cityId,
                   s.districtId AS districtId,
                   s.avatarUrl AS avatarUrl
            FROM Salon s
            WHERE s.isActive = true
              AND s.districtId = :districtId
            """)
    Page<SalonSearchProjection> findActiveByDistrictIdAsProjection(
            @Param("districtId") UUID districtId,
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
     * {@link #findActiveByCityIdAsProjection(UUID, Pageable)}.
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
    @Query("""
            SELECT s.id AS id,
                   s.name AS name,
                   s.cityId AS cityId,
                   s.districtId AS districtId,
                   s.avatarUrl AS avatarUrl
            FROM Salon s
            WHERE s.isActive = true
              AND s.cityId = :cityId
            """)
    Page<SalonSearchProjection> findActiveByCityIdAsProjection(
            @Param("cityId") UUID cityId,
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
     * {@link #findByIsActiveTrueAsProjection(Pageable)}.
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
    @Query("""
            SELECT s.id AS id,
                   s.name AS name,
                   s.cityId AS cityId,
                   s.districtId AS districtId,
                   s.avatarUrl AS avatarUrl
            FROM Salon s
            WHERE s.isActive = true
            """)
    Page<SalonSearchProjection> findByIsActiveTrueAsProjection(Pageable pageable);
}
