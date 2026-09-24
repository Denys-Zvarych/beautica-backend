package com.beautica.location;

import com.beautica.config.CacheConfig;
import com.beautica.location.repository.CityRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives {@link SettlementDisplayNameResolver} through the Spring proxy against the REAL
 * {@link CacheConfig} registration — a test that called the bean directly would bypass the
 * cache advisor and prove nothing (the self-invocation trap the resolver's Javadoc warns about).
 */
@SpringBootTest(
        classes = {SettlementDisplayNameResolver.class, CacheConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@DisplayName("SettlementDisplayNameResolver — @Cacheable behaviour")
class SettlementDisplayNameResolverCacheTest {

    @MockBean
    private CityRepository cityRepository;

    @Autowired
    private SettlementDisplayNameResolver resolver;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
        cacheManager.getCache(SettlementDisplayNameResolver.CACHE_SETTLEMENT_DISPLAY_NAMES).clear();
    }

    @Test
    @DisplayName("a second resolve of the same city does not hit the repository")
    void should_queryOnce_when_sameCityResolvedTwice() {
        UUID cityId = UUID.randomUUID();
        var names = new SettlementDisplayNames("Вінниця", "Вінницька");
        when(cityRepository.findDisplayNamesById(cityId)).thenReturn(Optional.of(names));

        Optional<SettlementDisplayNames> first = resolver.resolve(cityId);
        Optional<SettlementDisplayNames> second = resolver.resolve(cityId);

        assertThat(first).contains(names);
        assertThat(second).contains(names);
        verify(cityRepository, times(1)).findDisplayNamesById(cityId);
    }

    @Test
    @DisplayName("an unknown city is NOT cached — random-UUID enumeration mints no entries")
    void should_notCacheNegative_when_cityUnknown() {
        UUID cityId = UUID.randomUUID();
        when(cityRepository.findDisplayNamesById(cityId)).thenReturn(Optional.empty());

        resolver.resolve(cityId);
        resolver.resolve(cityId);

        verify(cityRepository, times(2)).findDisplayNamesById(cityId);
        assertThat(cacheManager.getCache(SettlementDisplayNameResolver.CACHE_SETTLEMENT_DISPLAY_NAMES)
                .get(cityId)).isNull();
    }

    @Test
    @DisplayName("a null cityId neither reaches Caffeine (null key) nor the repository")
    void should_returnEmptyWithoutCaching_when_cityIdIsNull() {
        Optional<SettlementDisplayNames> resolved = resolver.resolve(null);

        assertThat(resolved).isEmpty();
        verify(cityRepository, never()).findDisplayNamesById(any());
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} records stats (metered)")
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            SettlementDisplayNameResolver.CACHE_SETTLEMENT_DISPLAY_NAMES, "cityOblastId"})
    @DisplayName("both static-taxonomy caches are registered through registerMetered")
    void should_recordStats_when_cacheIsRegisteredMetered(String cacheName) {
        var cache = (org.springframework.cache.caffeine.CaffeineCache) cacheManager.getCache(cacheName);

        assertThat(cache).isNotNull();
        assertThat(cache.getNativeCache().policy().isRecordingStats())
                .as("registerCustomCache(...build()) does not record stats; registerMetered does")
                .isTrue();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("the warm resolve is a counted cache hit")
    void should_countHit_when_secondResolveServedFromCache() {
        UUID cityId = UUID.randomUUID();
        when(cityRepository.findDisplayNamesById(cityId))
                .thenReturn(Optional.of(new SettlementDisplayNames("Вінниця", "Вінницька")));
        var cache = (org.springframework.cache.caffeine.CaffeineCache)
                cacheManager.getCache(SettlementDisplayNameResolver.CACHE_SETTLEMENT_DISPLAY_NAMES);
        long hitsBefore = cache.getNativeCache().stats().hitCount();

        resolver.resolve(cityId);
        resolver.resolve(cityId);

        assertThat(cache.getNativeCache().stats().hitCount() - hitsBefore).isEqualTo(1);
    }
}
