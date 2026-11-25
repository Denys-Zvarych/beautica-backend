package com.beautica.catalog;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B501 (SVC-53) — {@link CatalogCacheEvictor#evictAll} in the REAL application context: the real
 * {@code CacheManager} from {@code CacheConfig} and the real JPA transaction manager (not a hand-driven
 * {@code TransactionSynchronizationManager}, as in {@code CatalogCacheEvictorTest}).
 */
@DisplayName("CatalogCacheEvictor (IT) — empties every catalog cache after commit, none on rollback")
class CatalogCacheEvictorIT extends AbstractIntegrationTest {

    private static final String KEY = "qa-key";
    /** Per-salon cache owned by SalonCatalogCacheEvictor — must survive evictAll. */
    private static final String NON_CATALOG_CACHE = "salon-service-catalog";

    @Autowired
    private CatalogCacheEvictor evictor;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void seedEveryCache() {
        for (String name : CatalogCacheEvictor.CACHE_NAMES) {
            cache(name).put(KEY, "seeded-" + name);
        }
        cache(NON_CATALOG_CACHE).put(KEY, "seeded-salon");
    }

    private Cache cache(String name) {
        return Objects.requireNonNull(cacheManager.getCache(name), "cache not registered in the real context: " + name);
    }

    private List<String> cachesStillHoldingKey() {
        return CatalogCacheEvictor.CACHE_NAMES.stream().filter(name -> cache(name).get(KEY) != null).toList();
    }

    @Test
    @DisplayName("precondition — every listed cache is registered and holds the seeded entry")
    void should_holdSeededEntry_inEveryListedCache() {
        assertThat(cachesStillHoldingKey()).containsExactlyElementsOf(CatalogCacheEvictor.CACHE_NAMES);
    }

    @Test
    @DisplayName("inside a committing transaction: nothing evicted before commit, every listed cache empty after")
    void should_evictEveryCatalogCache_onlyAfterCommit() {
        List<List<String>> beforeCommit = new ArrayList<>();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            evictor.evictAll();
            beforeCommit.add(cachesStillHoldingKey());
        });

        assertThat(beforeCommit.get(0)).as("still populated before commit (no pre-commit eviction)")
                .containsExactlyElementsOf(CatalogCacheEvictor.CACHE_NAMES);
        assertThat(cachesStillHoldingKey()).as("caches still holding the key after commit").isEmpty();
    }

    @Test
    @DisplayName("rolled-back transaction → no listed cache is evicted")
    void should_evictNothing_when_transactionRollsBack() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            evictor.evictAll();
            status.setRollbackOnly();
        });

        assertThat(cachesStillHoldingKey()).containsExactlyElementsOf(CatalogCacheEvictor.CACHE_NAMES);
    }

    @Test
    @DisplayName("outside a transaction → every listed cache is emptied immediately")
    void should_evictImmediately_when_noTransaction() {
        evictor.evictAll();

        assertThat(cachesStillHoldingKey()).isEmpty();
    }

    @Test
    @DisplayName("the per-salon catalog cache is NOT emptied by evictAll (owned by SalonCatalogCacheEvictor)")
    void should_keepSalonCatalogCache_when_evictAllCommits() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> evictor.evictAll());

        assertThat(cache(NON_CATALOG_CACHE).get(KEY)).isNotNull();
        assertThat(cache(NON_CATALOG_CACHE).get(KEY).get()).isEqualTo("seeded-salon");
    }
}
