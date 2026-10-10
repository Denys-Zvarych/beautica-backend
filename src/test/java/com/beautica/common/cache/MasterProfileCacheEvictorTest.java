package com.beautica.common.cache;

import com.beautica.auth.Role;
import com.beautica.search.service.SearchCacheNames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MasterProfileCacheEvictor — per-key, after-commit eviction of master-profile caches")
class MasterProfileCacheEvictorTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID masterId = UUID.randomUUID();
    private final String slug = "olena-k-ab12";

    private CacheManager cacheManager;
    private Cache detail;
    private Cache detailByUser;
    private Cache byUser;
    private Cache slugInfo;
    private Cache searchBrowse;
    private Cache searchQuery;
    private MasterProfileCacheEvictor evictor;

    @BeforeEach
    void setUp() {
        cacheManager = mock(CacheManager.class);
        detail = stub(MasterProfileCacheEvictor.MASTER_DETAIL_CACHE);
        detailByUser = stub(MasterProfileCacheEvictor.MASTER_DETAIL_BY_USER_CACHE);
        byUser = stub(MasterProfileCacheEvictor.MASTER_BY_USER_CACHE);
        slugInfo = stub(MasterProfileCacheEvictor.BOOKING_SLUG_INFO_CACHE);
        searchBrowse = stub(SearchCacheNames.MASTERS_BROWSE);
        searchQuery = stub(SearchCacheNames.MASTERS_QUERY);
        evictor = new MasterProfileCacheEvictor(cacheManager);
    }

    @AfterEach
    void clearSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("independent master: evicts each cache under its reader's exact @Cacheable key, clears only the query-keyed search caches")
    void should_evictEachCacheByItsKey_when_calledOutsideTransaction() {

        evictor.evictAfterCommit(userId, masterId, slug, Role.INDEPENDENT_MASTER);

        verify(detailByUser).evict(userId);
        verify(byUser).evict(userId);
        verify(detail).evict(masterId);
        verify(slugInfo).evict(slug);
        verify(searchBrowse).clear();
        verify(searchQuery).clear();
        verify(detail, never()).clear();
        verify(detailByUser, never()).clear();
        verify(slugInfo, never()).clear();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Role.class, names = {"SALON_MASTER", "SALON_OWNER"})
    @DisplayName("salon master / owner-master: per-key evictions still run, search caches are NOT cleared (344 c2)")
    void should_evictKeysButNotClearSearch_when_roleNotSearchVisible(Role role) {

        evictor.evictAfterCommit(userId, masterId, slug, role);

        verify(detailByUser).evict(userId);
        verify(byUser).evict(userId);
        verify(detail).evict(masterId);
        verify(slugInfo).evict(slug);
        verify(searchBrowse, never()).clear();
        verify(searchQuery, never()).clear();
    }

    @Test
    @DisplayName("inside a transaction the search clear is also deferred to afterCommit (344 c2)")
    void should_deferSearchClear_when_transactionActive() {
        TransactionSynchronizationManager.initSynchronization();

        evictor.evictAfterCommit(userId, masterId, slug, Role.INDEPENDENT_MASTER);

        verify(searchBrowse, never()).clear();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(searchBrowse).clear();
        verify(searchQuery).clear();
    }

    @Test
    @DisplayName("inside a transaction nothing is evicted until afterCommit runs")
    void should_deferEviction_when_transactionActive() {
        TransactionSynchronizationManager.initSynchronization();

        evictor.evictAfterCommit(userId, masterId, slug, Role.INDEPENDENT_MASTER);

        verify(detail, never()).evict(any());
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(detail).evict(masterId);
        verify(slugInfo).evict(slug);
    }

    @Test
    @DisplayName("a null slug skips booking-slug-info but still evicts the other keys")
    void should_skipSlugCache_when_slugNull() {

        evictor.evictAfterCommit(userId, masterId, null, Role.INDEPENDENT_MASTER);

        verify(slugInfo, never()).evict(any());
        verify(detail).evict(masterId);
    }

    private Cache stub(String name) {
        Cache cache = mock(Cache.class);
        when(cacheManager.getCache(name)).thenReturn(cache);
        return cache;
    }
}
