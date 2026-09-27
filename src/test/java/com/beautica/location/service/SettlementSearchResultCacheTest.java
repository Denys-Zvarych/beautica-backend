package com.beautica.location.service;

import com.beautica.config.CacheConfig;
import com.beautica.location.repository.CityRepository;
import com.beautica.location.repository.CityRepository.SettlementSearchRow;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cache-behaviour test for the Phase 329 {@code settlementSearch} cache on
 * {@link SettlementSearchService#runIndexedSearch(String)}.
 *
 * <p>Same shape as {@code LocationQueryServiceCacheTest}: a {@code webEnvironment=NONE} context of
 * just the service and the real {@link CacheConfig}, with {@link CityRepository} mocked, so the
 * Caffeine AOP proxy and the {@code @Lazy} self-proxy are both real. Every assertion counts
 * repository calls rather than inspecting the annotation, so removing {@code @Cacheable}, or a
 * {@code this.} call that bypasses the proxy, turns the "exactly once" assertions red.
 */
@SpringBootTest(
        classes = {SettlementSearchService.class, CacheConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@DisplayName("SettlementSearchService — settlementSearch result cache (Phase 329)")
class SettlementSearchResultCacheTest {

    /** The documented bound. Restated, not read off CacheConfig, so a silent resize fails here. */
    private static final long EXPECTED_MAXIMUM_SIZE = 1024L;

    @MockBean
    private CityRepository cityRepository;

    @Autowired
    private SettlementSearchService service;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void clearCaches() {
        cacheManager.getCache(SettlementSearchService.CACHE_SETTLEMENT_SEARCH).clear();
        cacheManager.getCache(SettlementSearchService.CACHE_MAJOR_SETTLEMENTS).clear();
        when(cityRepository.searchByName(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(List.of(row("Львів")));
        when(cityRepository.findMajorSettlements()).thenReturn(List.of(row("Київ")));
    }

    @SuppressWarnings("unchecked")
    private Cache<Object, Object> nativeSearchCache() {
        return (Cache<Object, Object>) cacheManager
                .getCache(SettlementSearchService.CACHE_SETTLEMENT_SEARCH).getNativeCache();
    }

    private static SettlementSearchRow row(String nameUk) {
        UUID id = UUID.randomUUID();
        return new SettlementSearchRow() {
            @Override public UUID getSettlementId() {
                return id;
            }

            @Override public String getNameUk() {
                return nameUk;
            }

            @Override public String getSettlementType() {
                return "CITY";
            }

            @Override public String getOblastNameUk() {
                return "Львівська";
            }

            @Override public String getHromadaNameUk() {
                return null;
            }
        };
    }

    @Test
    @DisplayName("the same term twice hits the repository exactly once")
    void should_queryRepositoryOnce_when_sameTermSearchedTwice() {
        service.search("льв");
        List<?> second = service.search("льв");

        verify(cityRepository, times(1)).searchByName(anyString(), eq("льв"), anyDouble(), anyInt());
        assertThat(second).hasSize(1);
    }

    @Test
    @DisplayName("«Льв» then «льв» share one entry — the key is the lower-cased term")
    void should_shareOneEntry_when_termsDifferOnlyInCase() {
        service.search("Льв");
        service.search("льв");

        verify(cityRepository, times(1)).searchByName(anyString(), anyString(), anyDouble(), anyInt());
        assertThat(nativeSearchCache().asMap()).containsOnlyKeys("льв");
    }

    @Test
    @DisplayName("a different term is a different key — «львів» after «льв» queries again")
    void should_queryAgain_when_aDifferentTermIsSearched() {
        service.search("льв");
        service.search("львів");

        verify(cityRepository, times(2)).searchByName(anyString(), anyString(), anyDouble(), anyInt());
        assertThat(nativeSearchCache().asMap()).containsOnlyKeys("льв", "львів");
    }

    @Test
    @DisplayName("a term the admission guard refuses never reaches the repository nor the cache")
    void should_neitherQueryNorCache_when_termIsBelowTheMinimum() {
        service.search("лв");
        service.search("•••");

        verify(cityRepository, never()).searchByName(anyString(), anyString(), anyDouble(), anyInt());
        assertThat(nativeSearchCache().asMap())
                .as("a refused term must never become a key — otherwise the admission guard stops "
                        + "fencing the cache's key space")
                .isEmpty();
    }

    @Test
    @DisplayName("«İİİ» is refused — lower-casing splits the run, and admission sees the lowered string")
    void should_notQueryOrCache_when_lowercasingBreaksTheTrigramRun() {
        // U+0130 lowers to «i» + U+0307 (a combining mark, not alphanumeric), so «İİİ» has a
        // 3-alnum run as typed and none once lowered. Admission must judge the string that is
        // cached and bound, not the one that was typed.
        service.search("İİİ");
        service.search("İ".repeat(50));

        verify(cityRepository, never()).searchByName(anyString(), anyString(), anyDouble(), anyInt());
        assertThat(nativeSearchCache().asMap()).isEmpty();
    }

    @Test
    @DisplayName("a term that lower-casing lengthens past 50 characters is refused")
    void should_notQueryOrCache_when_lowercasingLengthensTheTermPastTheCap() {
        // 47 x «İ» + «абв» is 50 characters as typed (clears @Size) and carries a real run, but
        // normalises to 97 — past the ceiling the cost guard's budgets are calibrated against.
        service.search("İ".repeat(47) + "абв");

        verify(cityRepository, never()).searchByName(anyString(), anyString(), anyDouble(), anyInt());
        assertThat(nativeSearchCache().asMap()).isEmpty();
    }

    @Test
    @DisplayName("a blank query takes the settlementMajors path and leaves settlementSearch empty")
    void should_leaveSearchCacheEmpty_when_queryIsBlank() {
        service.search("");

        verify(cityRepository, times(1)).findMajorSettlements();
        verify(cityRepository, never()).searchByName(anyString(), anyString(), anyDouble(), anyInt());
        assertThat(nativeSearchCache().asMap()).isEmpty();
    }

    @Test
    @DisplayName("the cache is bounded at 1024 entries")
    void should_beBoundedAt1024_when_cacheIsRegistered() {
        long maximum = nativeSearchCache().policy().eviction().orElseThrow().getMaximum();

        assertThat(maximum)
                .as("the bound is what makes a per-query cache on a permitAll endpoint safe — "
                        + "phase 326 rejected an UNBOUNDED one")
                .isEqualTo(EXPECTED_MAXIMUM_SIZE);
    }
}
