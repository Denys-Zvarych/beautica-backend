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

    Optional<Salon> findByIdAndOwnerId(UUID id, UUID ownerId);

    @Query("SELECT s FROM Salon s JOIN FETCH s.owner WHERE s.id = :id AND s.isActive = true")
    Optional<Salon> findByIdAndIsActiveTrueWithOwner(@Param("id") UUID id);

    /**
     * Filter active salons by the Phase 10.5 FK location filter
     * (district-primary) for the public salon search endpoint.
     *
     * <p>Replaces the removed free-text {@code findByFilter} (exact
     * string-equality on {@code city}/{@code region} — the Phase 10.5 bug).
     * No non-graph/legacy variant is kept alongside (§E): the free-text form
     * is deleted, not deprecated, because it produced silently wrong results
     * ("Київ" ≠ "Киев").
     *
     * <p>District-primary, read side: a supplied {@code districtId} wins;
     * otherwise {@code cityId}; both {@code null} → no location filter (all
     * active salons). {@code (:param IS NULL OR col = :param)} keeps a single
     * query covering every combination; the {@code Page} return type makes
     * Spring Data emit the matching {@code COUNT(*)} companion (no HAVING, so
     * the default count is correct).
     *
     * <p>Backed by {@code idx_salons_district_id} / {@code idx_salons_city_id}
     * (V54). A salon's discovery locality is its own {@code city_id} /
     * {@code district_id} — there is no salon-to-salon link to resolve through
     * (that resolution is the master-only {@code SALON_MASTER → salon} case).
     *
     * @param cityId     resolved discovery city id, or {@code null}
     * @param districtId resolved discovery district id, or {@code null}
     */
    @Query("""
            SELECT s FROM Salon s
            WHERE s.isActive = true
              AND (:districtId IS NOT NULL AND s.districtId = :districtId
                   OR :districtId IS NULL
                      AND (:cityId IS NULL OR s.cityId = :cityId))
            """)
    Page<Salon> findByLocation(
            @Param("cityId") UUID cityId,
            @Param("districtId") UUID districtId,
            Pageable pageable
    );
}
