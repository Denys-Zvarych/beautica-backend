package com.beautica.service.service;

import com.beautica.common.cache.SalonBookingInfoCacheEvictor;
import com.beautica.config.CacheConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

/**
 * Phase 384 audit F3 - ORDER of the two evictions in {@link SalonCatalogCacheEvictor#evict}. The public salon page
 * embeds {@code getSalonServiceCatalog}, so the {@code salon-service-catalog} entry must already be gone when the page
 * is evicted: a reader arriving between the two would otherwise rebuild the page from the STALE cached catalogue and
 * re-cache it. Runs through the REAL Spring cache proxy (a hand-called method would skip {@code @CacheEvict}).
 */
@SpringBootTest(
        classes = {SalonCatalogCacheEvictor.class, CacheConfig.class,
                com.beautica.common.cache.MasterCachePrefixEvictor.class, com.beautica.config.ClockConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("SalonCatalogCacheEvictor - the catalogue is evicted BEFORE the salon booking page")
class SalonCatalogCacheEvictorOrderingTest {

    @Autowired private SalonCatalogCacheEvictor evictor;
    @Autowired private CacheManager cacheManager;
    @MockBean private SalonBookingInfoCacheEvictor salonBookingInfoCacheEvictor;

    private final UUID salonId = UUID.randomUUID();

    @BeforeEach
    void seedCatalogue() {
        cacheManager.getCache("salon-service-catalog").put(salonId, "stale-catalogue");
    }

    @Test
    @DisplayName("should_haveEvictedCatalogue_when_salonPageIsEvicted")
    void should_haveEvictedCatalogue_when_salonPageIsEvicted() {
        Cache catalogue = cacheManager.getCache("salon-service-catalog");
        AtomicReference<Object> catalogueWhenPageEvicted = new AtomicReference<>("not-called");
        doAnswer(invocation -> {
            catalogueWhenPageEvicted.set(catalogue.get(salonId));
            return null;
        }).when(salonBookingInfoCacheEvictor).evictSalon(any());

        evictor.evict(salonId);

        verify(salonBookingInfoCacheEvictor).evictSalon(salonId);
        assertThat(catalogueWhenPageEvicted.get())
                .as("the stale catalogue must already be gone at the moment the page is evicted").isNull();
        assertThat(catalogue.get(salonId)).isNull();
    }

    @Test
    @DisplayName("evictCatalogOnly still evicts the catalogue and never touches the page")
    void should_evictCatalogueOnly_when_catalogOnlyCalled() {
        evictor.evictCatalogOnly(salonId);

        assertThat(cacheManager.getCache("salon-service-catalog").get(salonId)).isNull();
        org.mockito.Mockito.verifyNoInteractions(salonBookingInfoCacheEvictor);
    }

    @Test
    @DisplayName("phase 391 audit: both evict methods drop salon-catalog-snapshot together with the catalogue")
    void should_evictSnapshotWithCatalogue_when_eitherEvictCalled() {
        Cache snapshot = cacheManager.getCache(ServiceCatalogService.SALON_CATALOG_SNAPSHOT_CACHE);
        snapshot.put(salonId, "stale-snapshot");

        evictor.evictCatalogOnly(salonId);
        Object afterCatalogOnly = snapshot.get(salonId);
        snapshot.put(salonId, "stale-snapshot");
        evictor.evict(salonId);

        assertThat(afterCatalogOnly).isNull();
        assertThat(snapshot.get(salonId)).isNull();
    }

    @Test
    @DisplayName("phase 391 audit: the page embeds the snapshot, so it too is gone BEFORE the page is evicted")
    void should_haveEvictedSnapshot_when_salonPageIsEvicted() {
        Cache snapshot = cacheManager.getCache(ServiceCatalogService.SALON_CATALOG_SNAPSHOT_CACHE);
        snapshot.put(salonId, "stale-snapshot");
        AtomicReference<Object> snapshotWhenPageEvicted = new AtomicReference<>("not-called");
        doAnswer(invocation -> {
            snapshotWhenPageEvicted.set(snapshot.get(salonId));
            return null;
        }).when(salonBookingInfoCacheEvictor).evictSalon(any());

        evictor.evict(salonId);

        assertThat(snapshotWhenPageEvicted.get()).isNull();
    }
}
