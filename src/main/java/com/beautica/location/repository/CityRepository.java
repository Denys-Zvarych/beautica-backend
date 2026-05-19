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

    /**
     * Single-query taxonomy resolution for the Phase 10.6 most-specific-node
     * write rule: in <em>one</em> round-trip it answers all three facts
     * {@link com.beautica.location.LocalityWriteValidator} needs about a
     * {@code (cityId, districtId)} pair.
     *
     * <p>This fuses what used to be three sequential existence calls
     * ({@code cityRepository.existsById}, {@code existsByCityId},
     * {@code existsByIdAndCityId}) into a single SELECT, and is the query that
     * backs the {@code localityTaxonomyFacts} cache — so a warm save issues
     * <em>zero</em> taxonomy queries and a cold save issues exactly one.
     *
     * <p>Returns at most one row exposed as a typed {@link TaxonomyFactsRow}
     * interface projection rather than a raw {@code Object[]}. The {@code
     * Object[]} shape was ambiguous under Hibernate: when both correlated
     * {@code EXISTS} subqueries resolved to {@code FALSE}, the row was
     * sometimes materialised as a 1-element array (the outer {@code TRUE}
     * only), causing an {@code ArrayIndexOutOfBoundsException} downstream. A
     * Spring Data interface projection binds JPQL select aliases to getter
     * names, so the row shape is fixed regardless of subquery values.
     *
     * <ul>
     *   <li>{@link TaxonomyFactsRow#getCityExists()} — always {@code true}
     *       when a row is returned (the {@code WHERE c.id = :cityId} matched);
     *       the caller treats an empty result as "city does not exist".</li>
     *   <li>{@link TaxonomyFactsRow#getCityHasDistricts()} — correlated
     *       {@code EXISTS} over {@code city_districts} for this city (served
     *       by {@code idx_city_districts_city_id}).</li>
     *   <li>{@link TaxonomyFactsRow#getDistrictBelongsToCity()} — {@code
     *       false} when {@code districtId} is {@code null}; otherwise a
     *       correlated {@code EXISTS} that the district row exists <em>and</em>
     *       its {@code city_id} is {@code :cityId}.</li>
     * </ul>
     *
     * <p>The result is keyed/cached per {@code (cityId, districtId)} pair by
     * {@link com.beautica.location.LocalityTaxonomyLookup}; the taxonomy is
     * static Flyway-seed data, so a cached resolution can never go stale at
     * runtime (same rationale as the {@code location*} read caches).
     *
     * @param cityId     candidate city PK (never {@code null} — the validator
     *                   short-circuits a {@code null} city before calling)
     * @param districtId candidate district PK, or {@code null} when the caller
     *                   supplied no district
     * @return a single projection row, or empty when the city does not exist
     */
    @Query("""
            SELECT
                TRUE AS cityExists,
                (SELECT CASE WHEN COUNT(d1.id) > 0 THEN TRUE ELSE FALSE END
                   FROM CityDistrict d1 WHERE d1.city.id = c.id) AS cityHasDistricts,
                (SELECT CASE WHEN COUNT(d2.id) > 0 THEN TRUE ELSE FALSE END
                   FROM CityDistrict d2
                  WHERE d2.id = :districtId AND d2.city.id = c.id) AS districtBelongsToCity
            FROM City c
            WHERE c.id = :cityId
            """)
    Optional<TaxonomyFactsRow> resolveTaxonomyFacts(@Param("cityId") UUID cityId,
                                                    @Param("districtId") UUID districtId);

    /**
     * Typed Spring Data interface projection for
     * {@link #resolveTaxonomyFacts(UUID, UUID)}.
     *
     * <p>Getter names mirror the JPQL select aliases ({@code cityExists},
     * {@code cityHasDistricts}, {@code districtBelongsToCity}) so Spring Data
     * can bind each column unambiguously. This replaces the legacy
     * {@code Object[]} return shape, which under Hibernate could collapse to
     * a 1-element array when both correlated {@code EXISTS} subqueries
     * returned {@code FALSE} — the array-collapse caused a runtime
     * {@code ArrayIndexOutOfBoundsException} in
     * {@link com.beautica.location.LocalityTaxonomyLookup#resolve} on
     * {@code PATCH /me} writes whose target city / district combinations did
     * not match the seeded happy-path data.
     */
    interface TaxonomyFactsRow {

        /** Always {@code true} when a row is returned (city row matched). */
        Boolean getCityExists();

        /** {@code true} when the city defines any urban districts. */
        Boolean getCityHasDistricts();

        /**
         * {@code true} when the supplied {@code districtId} both exists and
         * is a child of {@code cityId}; {@code false} when {@code districtId}
         * is {@code null} or no such child row exists.
         */
        Boolean getDistrictBelongsToCity();
    }
}
