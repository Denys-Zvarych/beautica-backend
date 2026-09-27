package com.beautica.location.repository;

import com.beautica.AbstractIntegrationTest;
import com.beautica.location.KeyedSettlementDisplayNames;
import com.beautica.location.SettlementDisplayNames;
import com.beautica.location.entity.City;
import com.beautica.location.entity.SettlementType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Integration coverage for the {@link CityRepository} finders whose behaviour only Hibernate and
 * a real Postgres can settle: the CITY-bounded picker query, the batch oblast resolution behind
 * {@code SalonService#getOwnerSalons}, and the JOIN FETCH single-key lookup.
 *
 * <p>The seed assigns city/oblast ids via {@code gen_random_uuid()}, so every test resolves a
 * real seeded {@code (city, oblast)} pair from the DB rather than hardcoding a UUID — the tests
 * stay correct if the KATOTTH snapshot changes.
 *
 * <p><b>{@code findOblastIdById} coverage was removed with the method</b> (Phase 325 perf LOW).
 * It had exactly one production caller, {@code UserService#getProfile}, which now asks
 * {@code LocationQueryService#resolveCityOblastId} instead so the read goes through the
 * {@code cityOblastId} cache that exists for that question. Leaving the uncached scalar finder on
 * the repository would have been a §E-1 non-cached variant sitting beside the cached resolver —
 * the next caller reaching for the shorter name would silently re-open the bypass this phase
 * closed. The resolver's own path is covered by {@link #should_hydrateOblast_when_cityIdExists()}
 * below plus {@code LocationQueryServiceCacheTest}.
 */
@DisplayName("CityRepository — integration")
class CityRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private CityRepository cityRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ---------------------------------------------------------------------
    // Phase 325 — findByOblastIdAndSettlementTypeOrderByNameUkAsc
    //
    // The derived finder that replaced findByOblastIdOrderByNameUkAsc when V170/V171 took `cities`
    // from 356 rows to 25 698. Its whole reason to exist is the payload it does NOT return, and
    // nothing proved that against real data: the service-layer tests stub the repository, so they
    // would pass identically against the unfiltered predecessor.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("the oblast city finder returns ONLY settlements of type CITY — the ~100x payload guard")
    void should_returnOnlyCities_when_findingByOblastAndSettlementType() {
        UUID oblastId = (UUID) jdbcTemplate.queryForMap(
                "SELECT oblast_id FROM cities WHERE settlement_type = 'VILLAGE' "
                        + "GROUP BY oblast_id ORDER BY COUNT(*) DESC LIMIT 1").get("oblast_id");
        Integer allSettlements = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cities WHERE oblast_id = ?", Integer.class, oblastId);

        List<City> result =
                cityRepository.findByOblastIdAndSettlementTypeOrderByNameUkAsc(
                        oblastId, SettlementType.CITY);

        assertThat(result)
                .as("every returned row must be a CITY — a VILLAGE here is the unfiltered "
                        + "predecessor back in the picker")
                .isNotEmpty()
                .allSatisfy(c -> assertThat(c.getSettlementType()).isEqualTo(SettlementType.CITY));
        assertThat(result.size())
                .as("the picker must be a small fraction of the oblast's %d settlements, not all "
                        + "of them", allSettlements)
                .isLessThan(allSettlements / 10);
    }

    @Test
    @DisplayName("the oblast city finder returns rows ordered by name_uk, not by insertion order")
    void should_orderByUkrainianName_when_findingByOblastAndSettlementType() {
        UUID oblastId = (UUID) jdbcTemplate.queryForMap(
                "SELECT oblast_id FROM cities WHERE settlement_type = 'CITY' "
                        + "GROUP BY oblast_id ORDER BY COUNT(*) DESC LIMIT 1").get("oblast_id");

        List<String> names =
                cityRepository.findByOblastIdAndSettlementTypeOrderByNameUkAsc(
                                oblastId, SettlementType.CITY)
                        .stream().map(City::getNameUk).toList();

        assertThat(names)
                .as("the picker is alphabetical; V171 inserts in CSV order, so an unsorted finder "
                        + "would still look plausible on a spot check")
                .hasSizeGreaterThan(1)
                .isSortedAccordingTo(java.util.Comparator.naturalOrder());
    }

    @Test
    @DisplayName("the oblast city finder returns an empty list for an oblast id that matches nothing")
    void should_returnEmptyList_when_oblastIdMatchesNoSettlement() {
        List<City> result = cityRepository.findByOblastIdAndSettlementTypeOrderByNameUkAsc(
                UUID.randomUUID(), SettlementType.CITY);

        assertThat(result).as("an unknown oblast must yield an empty list, never throw").isEmpty();
    }

    @Test
    @DisplayName("the oblast city finder returns nothing for TOWN — the permanently empty KATOTTH bucket")
    void should_returnNothing_when_settlementTypeIsTown() {
        // TOWN is legal in the CHECK but the 2025-07-02 classifier retired category T. Pinning the
        // empty result documents that, and turns a future classifier update that reintroduces T
        // into a visible test failure rather than a silent behaviour change.
        UUID oblastId = (UUID) jdbcTemplate.queryForMap(
                "SELECT oblast_id FROM cities GROUP BY oblast_id ORDER BY COUNT(*) DESC LIMIT 1")
                .get("oblast_id");

        List<City> result = cityRepository.findByOblastIdAndSettlementTypeOrderByNameUkAsc(
                oblastId, SettlementType.TOWN);

        assertThat(result)
                .as("category T is retired in the imported classifier version")
                .isEmpty();
    }

    // ── findByIdWithOblast — JOIN FETCH single-key lookup used whenever a caller needs to
    // read city.getOblast() — including LocationQueryService#resolveCityOblastId, the SHARED
    // cached resolver every oblastId read now goes through. It returns the hydrated City entity,
    // so the thing actually under test is whether the LAZY oblast association comes back
    // INITIALIZED. The test
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

    // ── findDisplayNamesById / findDisplayNamesByIdIn — the saved-settlement label parts ─────
    // settlementType + the ambiguous-only hromada ride on the SettlementDisplayNames projection.
    // The CASE WHEN gate is JPQL that no unit test parses, and the batch form is a constructor
    // expression into a second record — both fail only at runtime, so they are proven here
    // against the Flyway-seeded taxonomy.

    @Test
    @DisplayName("findDisplayNamesById carries VILLAGE and the stored hromada for a settlement ambiguous in its oblast")
    void should_carryTypeAndHromada_when_settlementAmbiguousInOblast() {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT c.id, c.name_uk, c.hromada_name_uk, o.name_uk AS oblast_name_uk
                  FROM cities c JOIN oblasts o ON o.id = c.oblast_id
                 WHERE c.settlement_type = 'VILLAGE' AND c.ambiguous_in_oblast
                   AND c.hromada_name_uk IS NOT NULL
                 ORDER BY c.katotth_code LIMIT 1
                """);

        Optional<SettlementDisplayNames> names = cityRepository.findDisplayNamesById((UUID) row.get("id"));

        assertThat(names).contains(new SettlementDisplayNames(
                (String) row.get("name_uk"), (String) row.get("oblast_name_uk"),
                SettlementType.VILLAGE, (String) row.get("hromada_name_uk")));
    }

    @Test
    @DisplayName("findDisplayNamesById withholds a STORED hromada when the oblast already disambiguates the settlement")
    void should_nullHromada_when_settlementNotAmbiguousDespiteStoredHromada() {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT c.id, c.hromada_name_uk FROM cities c
                 WHERE c.settlement_type = 'CITY' AND NOT c.ambiguous_in_oblast
                   AND c.hromada_name_uk IS NOT NULL
                 ORDER BY c.katotth_code LIMIT 1
                """);
        assertThat((String) row.get("hromada_name_uk"))
                .as("precondition: the row stores a hromada, so null proves the ambiguous gate")
                .isNotBlank();

        SettlementDisplayNames names =
                cityRepository.findDisplayNamesById((UUID) row.get("id")).orElseThrow();

        assertThat(names.settlementType()).isEqualTo(SettlementType.CITY);
        assertThat(names.hromadaNameUk()).isNull();
    }

    @Test
    @DisplayName("findDisplayNamesByIdIn returns the SAME parts as the single-id finder plus each city's own oblastId, keyed by id, dropping unknown ids")
    void should_matchSingleIdProjection_when_batchResolving() {
        UUID ambiguous = jdbcTemplate.queryForObject("""
                SELECT id FROM cities WHERE ambiguous_in_oblast AND hromada_name_uk IS NOT NULL
                 ORDER BY katotth_code LIMIT 1
                """, UUID.class);
        // From a DIFFERENT oblast than `ambiguous`, so a swapped/collapsed oblastId cannot pass.
        UUID unambiguous = jdbcTemplate.queryForObject("""
                SELECT id FROM cities WHERE NOT ambiguous_in_oblast AND hromada_name_uk IS NOT NULL
                   AND oblast_id <> (SELECT oblast_id FROM cities WHERE id = ?)
                 ORDER BY katotth_code LIMIT 1
                """, UUID.class, ambiguous);

        List<KeyedSettlementDisplayNames> rows = cityRepository.findDisplayNamesByIdIn(
                Set.of(ambiguous, unambiguous, UUID.randomUUID()));

        assertThat(rows)
                .as("each row carries its city's OWN parent oblast id — the one join GET /salons/mine "
                        + "now relies on instead of a separate oblast-only batch")
                .extracting(KeyedSettlementDisplayNames::cityId, KeyedSettlementDisplayNames::oblastId)
                .containsExactlyInAnyOrder(
                        tuple(ambiguous, oblastIdOf(ambiguous)),
                        tuple(unambiguous, oblastIdOf(unambiguous)));
        assertThat(rows)
                .extracting(KeyedSettlementDisplayNames::cityId, KeyedSettlementDisplayNames::names)
                .containsExactlyInAnyOrder(
                        tuple(ambiguous, cityRepository.findDisplayNamesById(ambiguous).orElseThrow()),
                        tuple(unambiguous, cityRepository.findDisplayNamesById(unambiguous).orElseThrow()));
        assertThat(rows).filteredOn(r -> r.cityId().equals(ambiguous))
                .singleElement().extracting(KeyedSettlementDisplayNames::hromadaNameUk).isNotNull();
        assertThat(rows).filteredOn(r -> r.cityId().equals(unambiguous))
                .singleElement().extracting(KeyedSettlementDisplayNames::hromadaNameUk).isNull();
    }

    private UUID oblastIdOf(UUID cityId) {
        return jdbcTemplate.queryForObject("SELECT oblast_id FROM cities WHERE id = ?", UUID.class, cityId);
    }
}
