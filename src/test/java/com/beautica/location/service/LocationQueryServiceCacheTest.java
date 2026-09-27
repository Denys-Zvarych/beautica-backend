package com.beautica.location.service;

import com.beautica.config.CacheConfig;
import com.beautica.location.entity.City;
import com.beautica.location.entity.Oblast;
import com.beautica.location.entity.SettlementType;
import com.beautica.location.repository.CityDistrictRepository;
import com.beautica.location.repository.CityRepository;
import com.beautica.location.repository.OblastRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cache-behaviour test for {@link LocationQueryService} (Q7/Q19 — pins the
 * <em>contract</em>, not the annotation).
 *
 * <p>Mirrors the project cache-test pattern (see
 * {@code ServiceCatalogServiceCacheTest}): a minimal {@code webEnvironment=NONE}
 * context loads only the service + {@link CacheConfig} with mocked
 * repositories, so the Caffeine AOP proxy is real. Each test calls the service
 * twice and asserts the repository was hit exactly once — proving the second
 * call is served from the cache, never by re-counting {@code @Cacheable}.
 *
 * <p>Per-key isolation (Q19) is asserted for the keyed caches: a second
 * {@code oblastId}/{@code cityId} is a distinct cache key, so it must miss and
 * re-query — a regression to {@code @Cacheable} without a {@code key} (one
 * shared entry) would fail these.
 *
 * <p>The locality caches have <strong>no eviction path</strong> by design
 * (static Flyway-seed reference data; documented in {@link CacheConfig} and the
 * service Javadoc) — so there is intentionally no eviction test here; that
 * absence is the contract.
 */
@SpringBootTest(
        classes = {LocationQueryService.class, CacheConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@DisplayName("LocationQueryService — @Cacheable behaviour (Q7/Q19)")
class LocationQueryServiceCacheTest {

    @MockBean
    private OblastRepository oblastRepository;

    @MockBean
    private CityRepository cityRepository;

    @MockBean
    private CityDistrictRepository cityDistrictRepository;

    @Autowired
    private LocationQueryService service;

    @Autowired
    private CacheManager cacheManager;

    /** Rows in `cities` after Phase 325's V170 + V171 — the key space this cache is NOT. */
    private static final long SETTLEMENT_ROWS = 25_698L;

    @BeforeEach
    void clearCaches() {
        cacheManager.getCache("locationOblasts").clear();
        cacheManager.getCache("locationCitiesByOblast").clear();
        cacheManager.getCache("locationDistrictsByCity").clear();
        cacheManager.getCache("cityOblastId").clear();
    }

    @Test
    @DisplayName("second listOblasts is served from cache — OblastRepository hit once across two calls")
    void should_notReHitRepository_when_listOblastsCalledTwice() {
        when(oblastRepository.findWithSettlementTypeOrderByNameUkAsc(SettlementType.CITY)).thenReturn(List.of());

        service.listOblasts();
        service.listOblasts();

        verify(oblastRepository, times(1)).findWithSettlementTypeOrderByNameUkAsc(SettlementType.CITY);
    }

    @Test
    @DisplayName("second listCitiesByOblast(sameId) is cached — both repositories hit once across two calls")
    void should_notReHitRepository_when_listCitiesByOblastCalledTwiceWithSameId() {
        UUID oblastId = UUID.randomUUID();
        when(cityDistrictRepository.findCityIdsWithDistrictsByOblastId(oblastId))
                .thenReturn(Set.of());
        when(cityRepository.findByOblastIdAndSettlementTypeOrderByNameUkAsc(oblastId, SettlementType.CITY))
                .thenReturn(List.of());

        service.listCitiesByOblast(oblastId);
        service.listCitiesByOblast(oblastId);

        verify(cityDistrictRepository, times(1)).findCityIdsWithDistrictsByOblastId(oblastId);
        verify(cityRepository, times(1)).findByOblastIdAndSettlementTypeOrderByNameUkAsc(oblastId, SettlementType.CITY);
    }

    @Test
    @DisplayName("listCitiesByOblast caches per oblastId — a different oblast misses and re-queries (Q19 key isolation)")
    void should_cacheIndependentlyPerOblastId_when_differentOblastsRequested() {
        UUID oblastA = UUID.randomUUID();
        UUID oblastB = UUID.randomUUID();
        City cityA = City.builder().id(UUID.randomUUID())
                .oblast(Oblast.builder().id(oblastA).build())
                .katotthCode("a").nameUk("А").nameEn("A").build();
        when(cityDistrictRepository.findCityIdsWithDistrictsByOblastId(oblastA)).thenReturn(Set.of());
        when(cityDistrictRepository.findCityIdsWithDistrictsByOblastId(oblastB)).thenReturn(Set.of());
        when(cityRepository.findByOblastIdAndSettlementTypeOrderByNameUkAsc(oblastA, SettlementType.CITY)).thenReturn(List.of(cityA));
        when(cityRepository.findByOblastIdAndSettlementTypeOrderByNameUkAsc(oblastB, SettlementType.CITY)).thenReturn(List.of());

        service.listCitiesByOblast(oblastA); // miss → query
        service.listCitiesByOblast(oblastB); // distinct key → miss → query
        service.listCitiesByOblast(oblastA); // hit
        service.listCitiesByOblast(oblastB); // hit

        verify(cityRepository, times(1)).findByOblastIdAndSettlementTypeOrderByNameUkAsc(oblastA, SettlementType.CITY);
        verify(cityRepository, times(1)).findByOblastIdAndSettlementTypeOrderByNameUkAsc(oblastB, SettlementType.CITY);
    }

    @Test
    @DisplayName("second listDistrictsByCity(sameId) is cached — CityDistrictRepository hit once across two calls")
    void should_notReHitRepository_when_listDistrictsByCityCalledTwiceWithSameId() {
        UUID cityId = UUID.randomUUID();
        when(cityDistrictRepository.findByCityIdOrderByNameUkAsc(cityId))
                .thenReturn(List.of());

        service.listDistrictsByCity(cityId);
        service.listDistrictsByCity(cityId);

        verify(cityDistrictRepository, times(1)).findByCityIdOrderByNameUkAsc(cityId);
    }

    @Test
    @DisplayName("listDistrictsByCity caches per cityId — a different city misses and re-queries (Q19 key isolation)")
    void should_cacheIndependentlyPerCityId_when_differentCitiesRequested() {
        UUID cityA = UUID.randomUUID();
        UUID cityB = UUID.randomUUID();
        when(cityDistrictRepository.findByCityIdOrderByNameUkAsc(cityA)).thenReturn(List.of());
        when(cityDistrictRepository.findByCityIdOrderByNameUkAsc(cityB)).thenReturn(List.of());

        service.listDistrictsByCity(cityA); // miss
        service.listDistrictsByCity(cityB); // distinct key → miss
        service.listDistrictsByCity(cityA); // hit
        service.listDistrictsByCity(cityB); // hit

        verify(cityDistrictRepository, times(1)).findByCityIdOrderByNameUkAsc(cityA);
        verify(cityDistrictRepository, times(1)).findByCityIdOrderByNameUkAsc(cityB);
    }

    // ── resolveCityOblastId (Phase 240 perf MEDIUM fix — shared resolver for SalonService and
    // MasterService, replacing their private uncached copies) ─────────────────────────────────

    @Test
    @DisplayName("second resolveCityOblastId(sameId) is cached — CityRepository hit once across two calls")
    void should_notReHitRepository_when_resolveCityOblastIdCalledTwiceWithSameId() {
        UUID cityId = UUID.randomUUID();
        UUID oblastId = UUID.randomUUID();
        City city = City.builder().id(cityId)
                .oblast(Oblast.builder().id(oblastId).build())
                .katotthCode("c").nameUk("Місто").nameEn("City").build();
        when(cityRepository.findByIdWithOblast(cityId)).thenReturn(java.util.Optional.of(city));

        UUID first = service.resolveCityOblastId(cityId);
        UUID second = service.resolveCityOblastId(cityId);

        assertThat(first).isEqualTo(oblastId);
        assertThat(second).isEqualTo(oblastId);
        verify(cityRepository, times(1)).findByIdWithOblast(cityId);
    }

    @Test
    @DisplayName("resolveCityOblastId caches per cityId — a different city misses and re-queries (Q19 key isolation)")
    void should_cacheIndependentlyPerCityId_when_differentCitiesRequestedForOblastId() {
        UUID cityA = UUID.randomUUID();
        UUID cityB = UUID.randomUUID();
        UUID oblastA = UUID.randomUUID();
        UUID oblastB = UUID.randomUUID();
        City resolvedCityA = City.builder().id(cityA)
                .oblast(Oblast.builder().id(oblastA).build())
                .katotthCode("a").nameUk("А").nameEn("A").build();
        City resolvedCityB = City.builder().id(cityB)
                .oblast(Oblast.builder().id(oblastB).build())
                .katotthCode("b").nameUk("Б").nameEn("B").build();
        when(cityRepository.findByIdWithOblast(cityA)).thenReturn(java.util.Optional.of(resolvedCityA));
        when(cityRepository.findByIdWithOblast(cityB)).thenReturn(java.util.Optional.of(resolvedCityB));

        assertThat(service.resolveCityOblastId(cityA)).isEqualTo(oblastA); // miss → query
        assertThat(service.resolveCityOblastId(cityB)).isEqualTo(oblastB); // distinct key → miss → query
        assertThat(service.resolveCityOblastId(cityA)).isEqualTo(oblastA); // hit
        assertThat(service.resolveCityOblastId(cityB)).isEqualTo(oblastB); // hit

        verify(cityRepository, times(1)).findByIdWithOblast(cityA);
        verify(cityRepository, times(1)).findByIdWithOblast(cityB);
    }

    /**
     * Inverted in the Phase 325 security follow-up. This test used to assert that {@code null} IS
     * cached, which is what {@code unless = "#result == null"} now prevents: an authenticated
     * caller posting random UUIDs could otherwise mint a {@code NullValue} entry per request and
     * evict the live entries the cache exists to hold. A miss is the cheap case here — every
     * production caller has already established the city exists — so re-querying an unknown id is
     * the correct trade.
     */
    @Test
    @DisplayName("resolveCityOblastId returns null WITHOUT caching it — an unknown id re-queries every time")
    void should_notCacheNull_when_resolveCityOblastIdCalledWithUnknownCityId() {
        UUID cityId = UUID.randomUUID();
        when(cityRepository.findByIdWithOblast(cityId)).thenReturn(java.util.Optional.empty());

        UUID first = service.resolveCityOblastId(cityId);
        UUID second = service.resolveCityOblastId(cityId);

        assertThat(first).isNull();
        assertThat(second).isNull();
        verify(cityRepository, times(2)).findByIdWithOblast(cityId);
        assertThat(cacheManager.getCache("cityOblastId").get(cityId))
                .as("a negative lookup must leave NO entry behind — not even a NullValue wrapper, "
                        + "which would still occupy one of the bounded slots")
                .isNull();
    }

    /**
     * Q3 — pins the CONTRACT the {@code cityOblastId} resize is about, never the number it was
     * resized to. That literal is read back off the configured cache, so tuning it again does not
     * touch this test. What it forbids is the two ways the resize could do harm:
     *
     * <ol>
     *   <li>the cache losing its bound entirely — {@code CaffeineCacheManager}'s default is
     *       unbounded, and a dropped {@code maximumSize} leaves {@code policy().eviction()}
     *       empty (§F-5);</li>
     *   <li>the cache being re-sized to the SETTLEMENT TABLE again. That was the wrong premise
     *       this phase corrected: every caller keys on a stored {@code city_id}, so the reachable
     *       key space is cities-that-host-a-provider, not the 25 698 rows V170+V171 imported. A
     *       ceiling at or above the table size is the fingerprint of that mistake returning, and
     *       it costs megabytes of heap for keys that can never be requested.</li>
     * </ol>
     *
     * <p>The ceiling is asserted BEFORE the eviction probe on purpose: the probe inserts twice
     * the configured maximum, so on a cache sized to the table it would spend minutes proving
     * something the ceiling assertion already rejected in microseconds.
     *
     * <p>Caffeine's size eviction is amortised, so {@code cleanUp()} is required before reading
     * {@code estimatedSize()} — without it a passing assertion would be an accident of timing.
     */
    @Test
    @DisplayName("cityOblastId is bounded, sized to providers not to the settlement table, and evicts")
    void should_evictRatherThanGrow_when_moreCitiesAreResolvedThanTheCacheHolds() {
        com.github.benmanes.caffeine.cache.Cache<Object, Object> nativeCache =
                ((CaffeineCache) cacheManager.getCache("cityOblastId")).getNativeCache();

        long maximumSize = nativeCache.policy().eviction()
                .orElseThrow(() -> new AssertionError(
                        "cityOblastId declares no size-eviction policy — CaffeineCacheManager's "
                                + "default is UNBOUNDED, which §F-5 forbids"))
                .getMaximum();

        assertThat(maximumSize).as("a bound must exist and be usable").isPositive();
        assertThat(maximumSize)
                .as("the key space is cities-that-host-a-provider (a stored salons/users.city_id), "
                        + "NOT the %d-row settlement table V170+V171 imported — a ceiling at or "
                        + "above the table size means that premise came back", SETTLEMENT_ROWS)
                .isLessThan(SETTLEMENT_ROWS);

        when(cityRepository.findByIdWithOblast(any(UUID.class))).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            return java.util.Optional.of(City.builder().id(id)
                    .oblast(Oblast.builder().id(UUID.randomUUID()).build())
                    .katotthCode("c").nameUk("Місто").nameEn("City").build());
        });
        long inserted = maximumSize * 2;
        for (long i = 0; i < inserted; i++) {
            service.resolveCityOblastId(UUID.randomUUID());
        }
        nativeCache.cleanUp();

        assertThat(nativeCache.estimatedSize())
                .as("%d distinct city ids were resolved into a cache capped at %d", inserted, maximumSize)
                .isLessThanOrEqualTo(maximumSize)
                .isLessThan(inserted);
    }
}
