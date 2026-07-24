package com.beautica.dashboard.service;

import com.beautica.auth.Role;
import com.beautica.config.CacheConfig;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.salon.repository.SalonRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spring slice test verifying {@code @Cacheable} behaviour on
 * {@link DashboardService#getRevenueSummary}.
 *
 * <p>Pattern mirrors {@code ReviewServiceCacheTest}: load only the service under test and
 * {@link CacheConfig} (no web server, no DB), mock all collaborators with {@code @MockBean}.
 * Two assertions are made:
 * <ol>
 *   <li>Two calls with the same arguments → repository (EntityManager) hit exactly once.</li>
 *   <li>Two calls with different arguments each trigger their own repository hit.</li>
 * </ol>
 */
@SpringBootTest(
        classes = {DashboardService.class, CacheConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@DisplayName("DashboardService — @Cacheable(\"revenue-dashboard\") cache-hit behaviour")
class DashboardServiceCacheTest {

    @MockBean EntityManager            em;
    @MockBean SalonRepository          salonRepository;
    @MockBean MasterRepository         masterRepository;
    @MockBean MasterServiceRepository  masterServiceRepository;
    @MockBean Clock                    clock;

    @Autowired DashboardService dashboardService;
    @Autowired CacheManager     cacheManager;

    private static final LocalDate FROM = LocalDate.of(2026, 4, 1);
    private static final LocalDate TO   = LocalDate.of(2026, 4, 30);

    @BeforeEach
    void clearCache() {
        Cache cache = cacheManager.getCache("revenue-dashboard");
        if (cache != null) {
            cache.clear();
        }
    }

    /**
     * Second call with the same key must be served from cache — EntityManager query must not
     * be created a second time.
     */
    @Test
    @DisplayName("should_returnCachedResult_when_getRevenueSummaryCalledTwiceWithSameArgs")
    void should_returnCachedResult_when_getRevenueSummaryCalledTwiceWithSameArgs() {
        // Arrange
        UUID  actorId  = UUID.randomUUID();
        UUID  masterId = UUID.randomUUID();
        UUID  salonId  = UUID.randomUUID();

        stubClockAndSalon(actorId, salonId);
        Query query = stubEmptyQuery();

        // Act — call twice with identical arguments
        dashboardService.getRevenueSummary(
                actorId, Role.SALON_OWNER, FROM, TO, null, null, Optional.empty());
        dashboardService.getRevenueSummary(
                actorId, Role.SALON_OWNER, FROM, TO, null, null, Optional.empty());

        // Assert — EntityManager.createNativeQuery must be invoked only once
        verify(em, times(1)).createNativeQuery(anyString());
    }

    /**
     * Calls with different actor IDs must each trigger their own repository hit (no key collision).
     */
    @Test
    @DisplayName("should_hitRepositoryTwice_when_getRevenueSummaryCalledWithDifferentActorIds")
    void should_hitRepositoryTwice_when_getRevenueSummaryCalledWithDifferentActorIds() {
        // Arrange
        UUID actorId1 = UUID.randomUUID();
        UUID actorId2 = UUID.randomUUID();
        UUID salonId1 = UUID.randomUUID();
        UUID salonId2 = UUID.randomUUID();

        // Stub each actor's salon resolution separately
        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId1))
                .thenReturn(List.of(salonId1));
        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId2))
                .thenReturn(List.of(salonId2));

        stubEmptyQuery();

        // Act — different actor IDs = different cache keys
        dashboardService.getRevenueSummary(
                actorId1, Role.SALON_OWNER, FROM, TO, null, null, Optional.empty());
        dashboardService.getRevenueSummary(
                actorId2, Role.SALON_OWNER, FROM, TO, null, null, Optional.empty());

        // Assert — each distinct key triggers its own query
        verify(em, times(2)).createNativeQuery(anyString());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private void stubClockAndSalon(UUID actorId, UUID salonId) {
        when(clock.instant()).thenReturn(Instant.parse("2026-05-13T10:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId))
                .thenReturn(List.of(salonId));
    }

    @SuppressWarnings("unchecked")
    private Query stubEmptyQuery() {
        Query query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        return query;
    }
}
