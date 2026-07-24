package com.beautica.location.service;

import com.beautica.config.CacheConfig;
import com.beautica.location.entity.City;
import com.beautica.location.entity.Oblast;
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

import java.util.List;
import java.util.Set;
import java.util.UUID;

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

    @BeforeEach
    void clearCaches() {
        cacheManager.getCache("locationOblasts").clear();
        cacheManager.getCache("locationCitiesByOblast").clear();
        cacheManager.getCache("locationDistrictsByCity").clear();
    }

    @Test
    @DisplayName("second listOblasts is served from cache — OblastRepository hit once across two calls")
    void should_notReHitRepository_when_listOblastsCalledTwice() {
        when(oblastRepository.findAllByOrderByNameUkAsc()).thenReturn(List.of());

        service.listOblasts();
        service.listOblasts();

        verify(oblastRepository, times(1)).findAllByOrderByNameUkAsc();
    }

    @Test
    @DisplayName("second listCitiesByOblast(sameId) is cached — both repositories hit once across two calls")
    void should_notReHitRepository_when_listCitiesByOblastCalledTwiceWithSameId() {
        UUID oblastId = UUID.randomUUID();
        when(cityDistrictRepository.findCityIdsWithDistrictsByOblastId(oblastId))
                .thenReturn(Set.of());
        when(cityRepository.findByOblastIdOrderByNameUkAsc(oblastId))
                .thenReturn(List.of());

        service.listCitiesByOblast(oblastId);
        service.listCitiesByOblast(oblastId);

        verify(cityDistrictRepository, times(1)).findCityIdsWithDistrictsByOblastId(oblastId);
        verify(cityRepository, times(1)).findByOblastIdOrderByNameUkAsc(oblastId);
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
        when(cityRepository.findByOblastIdOrderByNameUkAsc(oblastA)).thenReturn(List.of(cityA));
        when(cityRepository.findByOblastIdOrderByNameUkAsc(oblastB)).thenReturn(List.of());

        service.listCitiesByOblast(oblastA); // miss → query
        service.listCitiesByOblast(oblastB); // distinct key → miss → query
        service.listCitiesByOblast(oblastA); // hit
        service.listCitiesByOblast(oblastB); // hit

        verify(cityRepository, times(1)).findByOblastIdOrderByNameUkAsc(oblastA);
        verify(cityRepository, times(1)).findByOblastIdOrderByNameUkAsc(oblastB);
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
}
