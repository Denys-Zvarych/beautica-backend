package com.beautica.catalog;

import com.beautica.config.CacheConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** B501 — {@link CatalogCacheEvictor} against the REAL {@link CacheConfig} cache manager. */
@DisplayName("CatalogCacheEvictor — empties every catalog cache, after commit only")
class CatalogCacheEvictorTest {

    private static final String KEY = "k";

    /** Any cache whose name says catalog / category / service type / suggestion is a catalog-cache candidate. */
    private static final Pattern CATALOG_NAME = Pattern.compile("(?i).*(categor|service-type|suggestion|catalog).*");

    /**
     * Candidates deliberately NOT emptied by {@code evictAll}: per-salon caches owned by {@code SalonCatalogCacheEvictor},
     * 60-s TTL. Adding a name here needs the same justification in {@link CatalogCacheEvictor}'s Javadoc.
     */
    private static final Set<String> NOT_PLATFORM_CATALOG = Set.of("salon-service-catalog", "salon-catalog-snapshot");

    private CacheManager cacheManager;
    private CatalogCacheEvictor evictor;

    @BeforeEach
    void setUp() {
        cacheManager = new CacheConfig().cacheManager(
                new DefaultListableBeanFactory().getBeanProvider(MeterRegistry.class));
        evictor = new CatalogCacheEvictor(cacheManager);
        CatalogCacheEvictor.CACHE_NAMES.forEach(name -> cacheManager.getCache(name).put(KEY, "value"));
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("outside a transaction every listed cache is empty after the call")
    void should_emptyEveryListedCache_when_noTransactionActive() {
        evictor.evictAll();

        assertThat(CatalogCacheEvictor.CACHE_NAMES).allSatisfy(name ->
                assertThat(cacheManager.getCache(name).get(KEY)).as(name).isNull());
    }

    @Test
    @DisplayName("the list equals the catalog subset of CacheConfig, so a new catalog cache fails here")
    void should_matchCatalogSubsetOfCacheConfig_when_comparingNames() {
        Set<String> catalogCaches = cacheManager.getCacheNames().stream()
                .filter(name -> CATALOG_NAME.matcher(name).matches())
                .filter(name -> !NOT_PLATFORM_CATALOG.contains(name))
                .collect(Collectors.toSet());

        assertThat(CatalogCacheEvictor.CACHE_NAMES).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(catalogCaches);
        assertThat(cacheManager.getCacheNames()).containsAll(NOT_PLATFORM_CATALOG);
    }

    @Test
    @DisplayName("inside a rolled-back transaction nothing is evicted")
    void should_evictNothing_when_transactionRollsBack() {
        TransactionSynchronizationManager.initSynchronization();

        evictor.evictAll();
        List<TransactionSynchronization> registered = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationUtils.invokeAfterCompletion(registered, TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(registered).hasSize(1);
        assertThat(CatalogCacheEvictor.CACHE_NAMES).allSatisfy(name ->
                assertThat(cacheManager.getCache(name).get(KEY)).as(name).isNotNull());
    }

    @Test
    @DisplayName("inside a transaction the caches empty only after commit")
    void should_evictOnlyAfterCommit_when_transactionActive() {
        TransactionSynchronizationManager.initSynchronization();

        evictor.evictAll();
        boolean presentBeforeCommit = CatalogCacheEvictor.CACHE_NAMES.stream()
                .allMatch(name -> cacheManager.getCache(name).get(KEY) != null);
        TransactionSynchronizationUtils.invokeAfterCommit(TransactionSynchronizationManager.getSynchronizations());

        assertThat(presentBeforeCommit).isTrue();
        assertThat(CatalogCacheEvictor.CACHE_NAMES).allSatisfy(name ->
                assertThat(cacheManager.getCache(name).get(KEY)).as(name).isNull());
    }
}
