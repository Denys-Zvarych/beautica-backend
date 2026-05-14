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
     * Filter salons by optional city/region for the public salon search endpoint.
     *
     * <p>Uses {@code (:param IS NULL OR col = :param)} so a single query covers all
     * four filter combinations (city only, region only, both, neither). Spring Data
     * generates a {@code COUNT(*)} companion query automatically for the {@code Page}
     * return type — no HAVING here so the default count query is correct.
     *
     * <p>Backed by the composite index {@code idx_salons_city_region} added in V35;
     * exact-match comparison on both columns preserves the leftmost-prefix index hit.
     */
    @Query("""
            SELECT s FROM Salon s
            WHERE s.isActive = true
              AND (:city IS NULL OR s.city = :city)
              AND (:region IS NULL OR s.region = :region)
            """)
    Page<Salon> findByFilter(
            @Param("city") String city,
            @Param("region") String region,
            Pageable pageable
    );
}
