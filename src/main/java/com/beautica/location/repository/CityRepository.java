package com.beautica.location.repository;

import com.beautica.location.entity.City;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data-access interface for {@link City} reference data.
 *
 * <p>All reads are read-only from the application perspective; no write
 * methods are defined here — mutations are exclusively via Flyway migrations.
 */
public interface CityRepository extends JpaRepository<City, UUID> {

    /**
     * Returns all cities within the given oblast, sorted alphabetically by
     * Ukrainian name. Used to populate the second tier of the cascading
     * locality picker after an oblast is selected.
     *
     * <p>Backed by {@code idx_cities_oblast_id}.
     *
     * @param oblastId surrogate PK of the parent oblast
     * @return cities in the oblast ordered by {@code name_uk ASC}
     */
    List<City> findByOblastIdOrderByNameUkAsc(UUID oblastId);

    /**
     * Looks up a city by its stable KATOTTH code.
     * Used by Phase 10.2 seed validation and Phase 10.3 FK resolution.
     *
     * @param katotthCode official KATOTTH city code
     * @return the matching city, if present
     */
    Optional<City> findByKatotthCode(String katotthCode);
}
