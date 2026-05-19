package com.beautica.location.repository;

import com.beautica.location.entity.City;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /**
     * Batch-resolves city {@code name_uk} labels for a set of city ids in a
     * single {@code IN (...)} query.
     *
     * <p>Used by {@link com.beautica.location.DiscoveryLocationResolver} to
     * stamp human-readable {@code cityLabel}s onto a whole page of search
     * results at once. The set-based form is deliberate (§E): the caller
     * collects the distinct city ids of the page and resolves them with
     * <em>one</em> query — never a per-row lookup. The 2-element projection
     * {@code [id, name_uk]} avoids hydrating the {@link City} entity (and its
     * LAZY {@code oblast}) just to read one column.
     *
     * @param ids distinct city ids appearing on the current result page
     * @return rows of {@code [UUID id, String nameUk]}; empty when {@code ids}
     *         is empty
     */
    @Query("SELECT c.id, c.nameUk FROM City c WHERE c.id IN :ids")
    List<Object[]> findNameUkByIdIn(@Param("ids") Collection<UUID> ids);
}
