package com.beautica.common.cache;

import com.beautica.search.service.SearchCacheNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BookabilitySearchCacheEvictor — search membership flips clear the right discovery caches")
class BookabilitySearchCacheEvictorTest {

    private static final String KEY = "page-0";

    private ConcurrentMapCacheManager cacheManager;
    private BookabilitySearchCacheEvictor evictor;

    @BeforeEach
    void setUp() {
        cacheManager = new ConcurrentMapCacheManager(
                SearchCacheNames.SALONS_BROWSE, SearchCacheNames.SALONS_QUERY, SearchCacheNames.SALONS_TOTAL,
                SearchCacheNames.MASTERS_BROWSE, SearchCacheNames.MASTERS_QUERY, SearchCacheNames.MASTERS_TOTAL);
        evictor = new BookabilitySearchCacheEvictor(cacheManager);
        for (String name : cacheManager.getCacheNames()) {
            cacheManager.getCache(name).put(KEY, "cached");
        }
    }

    @Test
    @DisplayName("salon-attached master — clears both salon-search halves, leaves master search alone")
    void should_clearSalonSearchOnly_when_masterBelongsToSalon() {
        evictor.clearSalonSearch();

        assertThat(cached(SearchCacheNames.SALONS_BROWSE)).isFalse();
        assertThat(cached(SearchCacheNames.SALONS_QUERY)).isFalse();
        assertThat(cached(SearchCacheNames.MASTERS_BROWSE)).isTrue();
        assertThat(cached(SearchCacheNames.MASTERS_QUERY)).isTrue();
    }

    @Test
    @DisplayName("independent master (null salon) — clears both master-search halves, leaves salon search alone")
    void should_clearMasterSearchOnly_when_masterIsIndependent() {
        evictor.clearMasterSearch();

        assertThat(cached(SearchCacheNames.MASTERS_BROWSE)).isFalse();
        assertThat(cached(SearchCacheNames.MASTERS_QUERY)).isFalse();
        assertThat(cached(SearchCacheNames.SALONS_BROWSE)).isTrue();
        assertThat(cached(SearchCacheNames.SALONS_QUERY)).isTrue();
    }

    @Test
    @DisplayName("salon-attached master — the salon-search totals memo is cleared too, the master one is kept")
    void should_clearSalonTotals_when_masterBelongsToSalon() {
        evictor.clearSalonSearch();

        assertThat(cached(SearchCacheNames.SALONS_TOTAL)).isFalse();
        assertThat(cached(SearchCacheNames.MASTERS_TOTAL)).isTrue();
    }

    @Test
    @DisplayName("independent master — the master-search totals memo is cleared too, the salon one is kept")
    void should_clearMasterTotals_when_masterIsIndependent() {
        evictor.clearMasterSearch();

        assertThat(cached(SearchCacheNames.MASTERS_TOTAL)).isFalse();
        assertThat(cached(SearchCacheNames.SALONS_TOTAL)).isTrue();
    }

    private boolean cached(String name) {
        return cacheManager.getCache(name).get(KEY) != null;
    }
}
