package com.beautica.location;

import com.beautica.config.CacheConfig;
import com.beautica.location.LocalityTaxonomyLookup.LocalityFacts;
import com.beautica.location.repository.CityRepository;
import com.beautica.location.repository.CityRepository.TaxonomyFactsRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cache-behaviour test for {@link LocalityTaxonomyLookup} — pins the
 * <em>perf acceptance</em> of the Phase 10.6 fix: a provider profile save
 * issues <strong>≤1</strong> taxonomy query (cold) and <strong>0</strong>
 * when warm-cached.
 *
 * <p>Mirrors the project cache-test pattern ({@code LocationQueryServiceCacheTest}
 * / {@code ServiceCatalogServiceCacheTest}): a minimal {@code webEnvironment=NONE}
 * context loads only the lookup + {@link CacheConfig} with a mocked
 * {@link CityRepository}, so the Caffeine AOP proxy is real. The
 * single fused repository method {@link CityRepository#resolveTaxonomyFacts}
 * is the only taxonomy round-trip — counting its invocations across repeated
 * {@code resolve(...)} calls is the direct measurement of the acceptance
 * criterion (it replaced the three former sequential existence calls).
 *
 * <p>The cache has <strong>no eviction path</strong> by design (static
 * Flyway-seed reference data; documented in {@link CacheConfig} and the
 * lookup Javadoc) — so there is intentionally no eviction test here; that
 * absence is the contract.
 */
@SpringBootTest(
        classes = {LocalityTaxonomyLookup.class, CacheConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@DisplayName("LocalityTaxonomyLookup — @Cacheable behaviour (≤1 query cold, 0 warm)")
class LocalityTaxonomyLookupCacheTest {

    @MockBean
    private CityRepository cityRepository;

    @Autowired
    private LocalityTaxonomyLookup lookup;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
        cacheManager.getCache("localityTaxonomyFacts").clear();
    }

    @Test
    @DisplayName("cold save = exactly ONE fused taxonomy query; warm save = ZERO")
    void should_issueAtMostOneTaxonomyQuery_when_resolveRepeatedForSamePair() {
        UUID cityId = UUID.randomUUID();
        UUID districtId = UUID.randomUUID();
        when(cityRepository.resolveTaxonomyFacts(cityId, districtId))
                .thenReturn(Optional.of(row(true, true, true)));

        LocalityFacts first = lookup.resolve(cityId, districtId);   // cold → 1 query
        LocalityFacts second = lookup.resolve(cityId, districtId);  // warm → 0 queries

        assertThat(first).isEqualTo(new LocalityFacts(true, true, true));
        assertThat(second).isEqualTo(first);
        verify(cityRepository, times(1)).resolveTaxonomyFacts(cityId, districtId);
    }

    @Test
    @DisplayName("absent city is cached too — repeated unknown-city save still issues only ONE query")
    void should_cacheCityAbsentSentinel_when_cityDoesNotExist() {
        UUID cityId = UUID.randomUUID();
        when(cityRepository.resolveTaxonomyFacts(cityId, null))
                .thenReturn(Optional.empty());

        LocalityFacts first = lookup.resolve(cityId, null);
        LocalityFacts second = lookup.resolve(cityId, null);

        assertThat(first.cityExists()).isFalse();
        assertThat(second).isEqualTo(first);
        verify(cityRepository, times(1)).resolveTaxonomyFacts(cityId, null);
    }

    @Test
    @DisplayName("distinct (city,district) pairs are independent cache keys (Q19 key isolation)")
    void should_cacheIndependentlyPerPair_when_differentDistrictsForSameCity() {
        UUID cityId = UUID.randomUUID();
        UUID districtA = UUID.randomUUID();
        UUID districtB = UUID.randomUUID();
        when(cityRepository.resolveTaxonomyFacts(cityId, districtA))
                .thenReturn(Optional.of(row(true, true, true)));
        when(cityRepository.resolveTaxonomyFacts(cityId, districtB))
                .thenReturn(Optional.of(row(true, true, false)));

        lookup.resolve(cityId, districtA); // miss → query
        lookup.resolve(cityId, districtB); // distinct key → miss → query
        lookup.resolve(cityId, districtA); // hit
        lookup.resolve(cityId, districtB); // hit

        verify(cityRepository, times(1)).resolveTaxonomyFacts(cityId, districtA);
        verify(cityRepository, times(1)).resolveTaxonomyFacts(cityId, districtB);
    }

    // ── Factual-variant pins ──────────────────────────────────────────────────
    //
    // These three cases pin every factual variant
    // CityRepository.resolveTaxonomyFacts can return — they exist explicitly
    // so a future regression cannot re-introduce a row-shape mismatch between
    // the JPQL projection and the lookup's mapping logic. Each variant is
    // stubbed via the same TaxonomyFactsRow interface the real repository
    // returns (no raw Object[]), which is the contract the fix locks in.

    @Test
    @DisplayName("factual variant — unknown cityId → Optional.empty() maps to all-false LocalityFacts")
    void should_returnCityAbsentFacts_when_repositoryReturnsEmpty() {
        UUID cityId = UUID.randomUUID();
        when(cityRepository.resolveTaxonomyFacts(cityId, null))
                .thenReturn(Optional.empty());

        LocalityFacts facts = lookup.resolve(cityId, null);

        assertThat(facts.cityExists()).isFalse();
        assertThat(facts.cityHasDistricts()).isFalse();
        assertThat(facts.districtBelongsToCity()).isFalse();
    }

    @Test
    @DisplayName("factual variant — city exists with no districts → cityHasDistricts=false")
    void should_mapFactsCorrectly_when_cityHasNoDistricts() {
        UUID cityId = UUID.randomUUID();
        when(cityRepository.resolveTaxonomyFacts(cityId, null))
                .thenReturn(Optional.of(row(true, false, false)));

        LocalityFacts facts = lookup.resolve(cityId, null);

        assertThat(facts.cityExists()).isTrue();
        assertThat(facts.cityHasDistricts()).isFalse();
        assertThat(facts.districtBelongsToCity()).isFalse();
    }

    @Test
    @DisplayName("factual variant — city has districts + district is child → districtBelongsToCity=true")
    void should_mapFactsCorrectly_when_districtIsChildOfCity() {
        UUID cityId = UUID.randomUUID();
        UUID districtId = UUID.randomUUID();
        when(cityRepository.resolveTaxonomyFacts(cityId, districtId))
                .thenReturn(Optional.of(row(true, true, true)));

        LocalityFacts facts = lookup.resolve(cityId, districtId);

        assertThat(facts.cityExists()).isTrue();
        assertThat(facts.cityHasDistricts()).isTrue();
        assertThat(facts.districtBelongsToCity()).isTrue();
    }

    /**
     * Hand-rolled {@link TaxonomyFactsRow} stub for Mockito. The repository
     * returns a Spring-Data-generated proxy of this interface at runtime; in
     * tests we supply a plain anonymous implementation so {@code .map(...)}
     * inside {@link LocalityTaxonomyLookup#resolve} reads through the typed
     * getters — the same code path production exercises.
     */
    private static TaxonomyFactsRow row(boolean cityExists,
                                        boolean cityHasDistricts,
                                        boolean districtBelongsToCity) {
        return new TaxonomyFactsRow() {
            @Override public Boolean getCityExists() { return cityExists; }
            @Override public Boolean getCityHasDistricts() { return cityHasDistricts; }
            @Override public Boolean getDistrictBelongsToCity() { return districtBelongsToCity; }
        };
    }
}
