package com.beautica.location.repository;

import com.beautica.AbstractIntegrationTest;
import com.beautica.location.entity.City;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Integration coverage for {@link CityRepository#findOblastIdById} — the
 * single-id city→oblast FK lookup that backs the {@code oblastId} field on
 * {@code GET /users/me} (so the mobile Location-edit cascade can pre-select the
 * oblast tier without scanning every oblast's city list).
 *
 * <p>The seed assigns city/oblast ids via {@code gen_random_uuid()}, so the
 * known-id test resolves a real seeded {@code (city, oblast)} pair from the DB
 * rather than hardcoding a UUID — the test stays correct if the KATOTTH snapshot
 * changes.
 */
@DisplayName("CityRepository.findOblastIdById — integration")
class CityRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private CityRepository cityRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("findOblastIdById resolves a seeded city id to its parent oblast id")
    void should_resolveOblastId_when_cityIdExists() {
        Map<String, Object> seeded = jdbcTemplate.queryForMap(
                "SELECT id, oblast_id FROM cities LIMIT 1");
        UUID seededCityId = (UUID) seeded.get("id");
        UUID expectedOblastId = (UUID) seeded.get("oblast_id");

        Optional<UUID> resolved = cityRepository.findOblastIdById(seededCityId);

        assertThat(resolved)
                .as("a seeded city id must resolve to its parent oblast_id FK")
                .contains(expectedOblastId);
    }

    @Test
    @DisplayName("findOblastIdById returns empty Optional for an unknown id")
    void should_returnEmpty_when_cityIdUnknown() {
        UUID unknownId = UUID.randomUUID();

        Optional<UUID> resolved = cityRepository.findOblastIdById(unknownId);

        assertThat(resolved)
                .as("an id that matches no city row must yield an empty Optional, never null/throw")
                .isEmpty();
    }

    // ── findOblastIdsByIdIn — batch sibling backing SalonService#getOwnerSalons ─────────
    //
    // This JPQL (SELECT c.id, c.oblast.id FROM City c WHERE c.id IN :ids) is only ever
    // exercised against a Mockito mock in SalonServiceMultiTest — nothing proves Hibernate
    // can actually parse it or that it returns the 2-column [cityId, oblastId] Object[] shape
    // SalonService#resolveOblastIdsByCityIds expects (row[0]/row[1] cast to UUID). A malformed
    // @Query fails at context startup or at execution, never at compile — so this IT is the
    // only thing that would catch a broken projection before production.

    @Test
    @DisplayName("findOblastIdsByIdIn resolves each city id to its OWN parent oblast id, not a neighbour's")
    void should_resolveDistinctOblastIds_when_citiesSpanDifferentOblasts() {
        // Deliberately pick two cities from DIFFERENT oblasts (not two cities that happen to
        // share one) — if the query's map-building ever swapped rows or collapsed to a single
        // oblast, a same-oblast fixture would still pass by coincidence and prove nothing.
        Map<String, Object> pair = jdbcTemplate.queryForMap("""
                SELECT c1.id AS city1_id, c1.oblast_id AS oblast1_id,
                       c2.id AS city2_id, c2.oblast_id AS oblast2_id
                FROM cities c1
                JOIN cities c2 ON c2.oblast_id <> c1.oblast_id
                LIMIT 1
                """);
        UUID city1 = (UUID) pair.get("city1_id");
        UUID oblast1 = (UUID) pair.get("oblast1_id");
        UUID city2 = (UUID) pair.get("city2_id");
        UUID oblast2 = (UUID) pair.get("oblast2_id");

        List<Object[]> rows = cityRepository.findOblastIdsByIdIn(Set.of(city1, city2));

        assertThat(rows)
                .as("each seeded city id must map back to its own oblast_id, never the other city's")
                .extracting(row -> (UUID) row[0], row -> (UUID) row[1])
                .containsExactlyInAnyOrder(tuple(city1, oblast1), tuple(city2, oblast2));
    }

    @Test
    @DisplayName("findOblastIdsByIdIn ignores ids that match no city row")
    void should_excludeUnknownIds_when_someIdsDoNotMatchAnyCity() {
        Map<String, Object> seeded = jdbcTemplate.queryForMap(
                "SELECT id, oblast_id FROM cities LIMIT 1");
        UUID knownCityId = (UUID) seeded.get("id");
        UUID knownOblastId = (UUID) seeded.get("oblast_id");
        UUID unknownCityId = UUID.randomUUID();

        List<Object[]> rows = cityRepository.findOblastIdsByIdIn(Set.of(knownCityId, unknownCityId));

        assertThat(rows)
                .as("an id with no matching city row must be silently dropped, not a null/error row")
                .extracting(row -> (UUID) row[0], row -> (UUID) row[1])
                .containsExactly(tuple(knownCityId, knownOblastId));
    }

    @Test
    @DisplayName("findOblastIdsByIdIn returns an empty list for an empty id collection")
    void should_returnEmptyList_when_idsCollectionIsEmpty() {
        List<Object[]> rows = cityRepository.findOblastIdsByIdIn(Collections.emptySet());

        assertThat(rows).isEmpty();
    }

    // ── findByIdWithOblast — JOIN FETCH single-key lookup used whenever a caller needs to
    // read city.getOblast() (e.g. City admin/detail reads). Unlike its scalar sibling
    // findOblastIdById, this returns the hydrated City entity itself, so the thing actually
    // under test is whether the LAZY oblast association comes back INITIALIZED. The test
    // class has no @Transactional and AbstractIntegrationTest opens no surrounding session,
    // so each repository call runs (and its Hibernate session closes) before control returns
    // here — touching city.getOblast().getId() afterwards only succeeds if the JOIN FETCH
    // actually populated the association; a plain `SELECT c FROM City c WHERE c.id = :id`
    // would leave the proxy uninitialized and throw LazyInitializationException at that point.

    @Test
    @DisplayName("findByIdWithOblast resolves a seeded city id with its oblast association hydrated")
    void should_hydrateOblast_when_cityIdExists() {
        Map<String, Object> seeded = jdbcTemplate.queryForMap("""
                SELECT c.id AS city_id, o.id AS oblast_id, o.name_uk AS oblast_name_uk
                FROM cities c JOIN oblasts o ON o.id = c.oblast_id
                LIMIT 1
                """);
        UUID seededCityId = (UUID) seeded.get("city_id");
        UUID expectedOblastId = (UUID) seeded.get("oblast_id");
        String expectedOblastNameUk = (String) seeded.get("oblast_name_uk");

        Optional<City> resolved = cityRepository.findByIdWithOblast(seededCityId);

        assertThat(resolved).as("a seeded city id must resolve to a City").isPresent();
        City city = resolved.get();
        assertThat(city.getId()).isEqualTo(seededCityId);
        // Touching only getOblast().getId() is NOT a real assertion of hydration: a Hibernate
        // LAZY ManyToOne proxy already knows its own identifier from the owning row's FK column
        // without triggering initialization, so getId() succeeds even against an uninitialized
        // proxy (a plain, non-JOIN-FETCH SELECT). Reading a NON-KEY field — name_uk, which is
        // only available once the proxy is actually initialized — is what genuinely depends on
        // the JOIN FETCH: an uninitialized proxy reached after the repository call's own
        // session/transaction has closed throws LazyInitializationException on this next line.
        assertThat(city.getOblast().getNameUk())
                .as("hydrated oblast's name_uk must be readable without a LazyInitializationException, "
                        + "and must belong to the city's OWN parent oblast, not some other one")
                .isEqualTo(expectedOblastNameUk);
        assertThat(city.getOblast().getId())
                .as("hydrated oblast must be the city's OWN parent, not some other oblast")
                .isEqualTo(expectedOblastId);
    }

    @Test
    @DisplayName("findByIdWithOblast returns empty Optional for an unknown id, never throws")
    void should_returnEmpty_when_cityIdUnknownForFindByIdWithOblast() {
        UUID unknownId = UUID.randomUUID();

        Optional<City> resolved = cityRepository.findByIdWithOblast(unknownId);

        assertThat(resolved)
                .as("an id that matches no city row must yield an empty Optional, never null/throw")
                .isEmpty();
    }
}
