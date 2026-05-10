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
