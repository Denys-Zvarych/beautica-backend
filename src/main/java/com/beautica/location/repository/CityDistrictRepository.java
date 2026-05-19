package com.beautica.location.repository;

import com.beautica.location.entity.CityDistrict;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Set;
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
     * <p>Used by the <em>single-city write-validation</em> rule in Phase 10.6:
     * if a city has urban districts, a booking/salon address must specify a
     * district; if it does not, the city itself is the locality leaf and no
     * district is required.
     *
     * <p>Backed by {@code idx_city_districts_city_id}; the index seek returns
     * immediately on the first matching row.
     *
     * <p><strong>Not a §E "non-graph variant" violation:</strong> this is a
     * deliberately distinct single-key predicate for the write path. The
     * list/picker path uses the set-based
     * {@link #findCityIdsWithDistrictsByOblastId(UUID)} instead — calling
     * {@code existsByCityId} per city in a loop would be the N+1 §E forbids.
     *
     * @param cityId surrogate PK of the city to test
     * @return {@code true} if at least one urban district belongs to this city
     */
    boolean existsByCityId(UUID cityId);

    /**
     * Returns the set of city ids (within the given oblast) that have at least
     * one urban district.
     *
     * <p>This is the set-based backing for {@code CityResponse#hasDistricts}
     * in the cascading picker: the {@code LocationQueryService} issues this
     * <em>once</em> per "cities by oblast" request and tests membership of each
     * city id in-memory — never a per-row {@code existsByCityId} call
     * (§E: no N+1 in the picker).
     *
     * <p>The result is scoped to {@code oblastId} so the set never grows
     * beyond the cities actually being listed. The {@code c.oblast.id}
     * predicate joins {@code cities} (PK lookup) and the
     * {@code city_districts.city_id} grouping is served by
     * {@code idx_city_districts_city_id}.
     *
     * @param oblastId surrogate PK of the oblast whose cities are being listed
     * @return distinct city ids in that oblast having one or more urban districts
     */
    @Query("""
            SELECT DISTINCT d.city.id
            FROM CityDistrict d
            WHERE d.city.oblast.id = :oblastId
            """)
    Set<UUID> findCityIdsWithDistrictsByOblastId(@Param("oblastId") UUID oblastId);

    /**
     * Batch-resolves urban-district {@code name_uk} labels for a set of
     * district ids in a single {@code IN (...)} query.
     *
     * <p>Used by {@link com.beautica.location.DiscoveryLocationResolver} to
     * stamp {@code districtLabel}s onto a whole page of search results at
     * once. Set-based on purpose (§E): the caller collects the distinct
     * district ids of the page and resolves them with <em>one</em> query —
     * never per-row. The 2-element projection {@code [id, name_uk]} avoids
     * hydrating the {@link CityDistrict} entity (and its LAZY {@code city}).
     *
     * @param ids distinct district ids appearing on the current result page
     * @return rows of {@code [UUID id, String nameUk]}; empty when {@code ids}
     *         is empty
     */
    @Query("SELECT d.id, d.nameUk FROM CityDistrict d WHERE d.id IN :ids")
    List<Object[]> findNameUkByIdIn(@Param("ids") Collection<UUID> ids);
}
