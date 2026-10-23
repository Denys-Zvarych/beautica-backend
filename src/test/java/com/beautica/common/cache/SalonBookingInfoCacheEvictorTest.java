package com.beautica.common.cache;

import com.beautica.booking.dto.SalonBookingSlugInfoResponse;
import com.beautica.booking.dto.SalonBookingSlugMasterItem;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Real Caffeine cache + real {@link MasterCachePrefixEvictor} (called directly, so its {@code @Async}
 * runs inline): proves the value-scan predicates hit exactly the intended entries — a Caffeine-backed
 * cache, so the coarse {@code clear()} fallback can never make a wrong predicate pass.
 */
@DisplayName("SalonBookingInfoCacheEvictor — per-salon / per-master-slug value eviction, after commit")
class SalonBookingInfoCacheEvictorTest {

    private final UUID salonA = UUID.randomUUID();
    private final UUID salonB = UUID.randomUUID();

    private Cache cache;
    private SalonBookingInfoCacheEvictor evictor;

    @BeforeEach
    void setUp() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.registerCustomCache(SalonBookingInfoCacheEvictor.CACHE, Caffeine.newBuilder().maximumSize(10).build());
        cache = manager.getCache(SalonBookingInfoCacheEvictor.CACHE);
        evictor = new SalonBookingInfoCacheEvictor(new MasterCachePrefixEvictor(manager), manager);
        cache.put("salon-a-1111", page(salonA, "anna-b-aa11"));
        cache.put("salon-b-2222", page(salonB, "olha-ch-bb22"));
    }

    @AfterEach
    void clearSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static SalonBookingSlugInfoResponse page(UUID salonId, String masterSlug) {
        return new SalonBookingSlugInfoResponse(salonId, "Salon", "Вінниця", null, null, null, 0, true,
                List.of(new SalonBookingSlugMasterItem(masterSlug, "Name", null, null, null, 0)),
                null, null, null, java.time.Instant.EPOCH, null, null, List.of(), List.of(), List.of(), List.of());
    }

    @Test
    @DisplayName("evictSalon removes only that salon's entry")
    void should_evictOnlyMatchingSalon_when_evictSalonCalled() {

        evictor.evictSalon(salonA);

        assertThat(cache.get("salon-a-1111")).isNull();
        assertThat(cache.get("salon-b-2222")).isNotNull();
    }

    @Test
    @DisplayName("evictByMasterSlug removes only the pages listing that master")
    void should_evictOnlyPagesListingMaster_when_evictByMasterSlugCalled() {

        evictor.evictByMasterSlug("olha-ch-bb22");

        assertThat(cache.get("salon-a-1111")).isNotNull();
        assertThat(cache.get("salon-b-2222")).isNull();
    }

    @Test
    @DisplayName("null salon id / master slug → no-op")
    void should_keepEverything_when_keyNull() {

        evictor.evictSalon(null);
        evictor.evictByMasterSlug(null);
        evictor.evictSalonAfterCommit(null);

        assertThat(cache.get("salon-a-1111")).isNotNull();
        assertThat(cache.get("salon-b-2222")).isNotNull();
    }

    @Test
    @DisplayName("inside a transaction evictSalonAfterCommit waits for afterCommit (§F-2)")
    void should_deferEviction_when_transactionActive() {
        TransactionSynchronizationManager.initSynchronization();

        evictor.evictSalonAfterCommit(salonA);

        assertThat(cache.get("salon-a-1111")).as("not before commit").isNotNull();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(cache.get("salon-a-1111")).isNull();
        assertThat(cache.get("salon-b-2222")).isNotNull();
    }

    @Test
    @DisplayName("outside a transaction evictSalonAfterCommit evicts immediately")
    void should_evictImmediately_when_noTransaction() {

        evictor.evictSalonAfterCommit(salonB);

        assertThat(cache.get("salon-b-2222")).isNull();
        assertThat(cache.get("salon-a-1111")).isNotNull();
    }

    @Test
    @DisplayName("378 audit: evictSlugAfterCommit drops exactly that key, only after commit")
    void should_evictOnlyThatKeyAfterCommit_when_evictSlugAfterCommitInTransaction() {
        TransactionSynchronizationManager.initSynchronization();

        evictor.evictSlugAfterCommit("salon-a-1111");

        assertThat(cache.get("salon-a-1111")).as("not before commit").isNotNull();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(cache.get("salon-a-1111")).isNull();
        assertThat(cache.get("salon-b-2222")).isNotNull();
    }

    @Test
    @DisplayName("378 audit: null slug → no-op")
    void should_keepEverything_when_slugNull() {

        evictor.evictSlugAfterCommit(null);
        evictor.evictSlug(null);

        assertThat(cache.get("salon-a-1111")).isNotNull();
        assertThat(cache.get("salon-b-2222")).isNotNull();
    }

    @Test
    @DisplayName("378 audit security LOW: a per-key evict also removes a sync=true load IN FLIGHT on that key (the value scan cannot)")
    void should_dropInFlightLoad_when_slugEvictedDuringLoad() throws Exception {
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> load = pool.submit(() -> cache.get("salon-c-3333", () -> {
                loading.countDown();
                release.await(10, TimeUnit.SECONDS);
                return page(UUID.randomUUID(), "stale-cc33");
            }));
            assertThat(loading.await(10, TimeUnit.SECONDS)).isTrue();
            AtomicReference<Thread> evictThread = new AtomicReference<>();
            Future<?> evict = pool.submit(() -> {
                evictThread.set(Thread.currentThread());
                evictor.evictSlug("salon-c-3333");
            });
            await().atMost(Duration.ofSeconds(10)).until(() -> evictThread.get() != null
                    && evictThread.get().getState() == Thread.State.BLOCKED);

            release.countDown();
            load.get(10, TimeUnit.SECONDS);
            evict.get(10, TimeUnit.SECONDS);

            assertThat(cache.get("salon-c-3333")).as("the in-flight (stale) load does not survive the evict").isNull();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
