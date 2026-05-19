package com.beautica.location.repository;

import com.beautica.location.entity.CityDistrict;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Data-access interface for {@link CityDistrict} reference data.
 *
 * <p>All reads are read-only from the application perspective; no write
 * methods are defined here — mutations are exclusively via Flyway migrations.
 */
public interface CityDistrictRepository extends JpaRepository<CityDistrict, UUID> {

    /**
     * Returns all urban districts (KATOTTH category "B") within the given city,
     * sorted alphabetically by Ukrainian name. Used to populate the third tier
     * of the cascading locality picker when a city that has urban districts is
     * selected.
     *
     * <p>Backed by {@code idx_city_districts_city_id}.
     *
     * @param cityId surrogate PK of the parent city
     * @return urban districts in the city ordered by {@code name_uk ASC}
     */
    List<CityDistrict> findByCityIdOrderByNameUkAsc(UUID cityId);

    /**
     * Checks whether the given city has any urban districts defined.
     *
     * <p>Used by the write-validation rule in Phase 10.6: if a city has urban
     * districts, a booking/salon address must specify a district; if it does not,
     * the city itself is the locality leaf and no district is required.
     *
     * <p>Backed by {@code idx_city_districts_city_id}; the index seek returns
     * immediately on the first matching row.
     *
     * @param cityId surrogate PK of the city to test
     * @return {@code true} if at least one urban district belongs to this city
     */
    boolean existsByCityId(UUID cityId);
}
