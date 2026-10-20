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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
        evictor = new SalonBookingInfoCacheEvictor(new MasterCachePrefixEvictor(manager));
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
                List.of(new SalonBookingSlugMasterItem(masterSlug, "Name", null, null)));
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
}
