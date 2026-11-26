package com.beautica.service.service;

import com.beautica.config.CacheConfig;
import com.beautica.notification.EmailService;
import com.beautica.notification.inapp.service.InAppNotificationService;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.service.entity.PlatformCategory;
import com.beautica.service.repository.PlatformCategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code @Cacheable} behaviour for the approved-category read path.
 *
 * <p>Loads only the service-under-test, the real {@link CacheConfig}, and a fixed
 * {@link Clock}; all collaborators are mocks. Proves PERF-MEDIUM: repeat reads of
 * {@code listApproved} hit the {@code approved-categories} cache (one repository query) — all
 * within one JVM, no DB. Approve eviction is {@code CatalogCacheEvictor.evictPlatformCatalog()} on the portal path (B503 D5;
 * the token path's {@code @CacheEvict} set went with B545), covered by {@code CatalogRequestAdminServiceTest} and
 * {@code InternalCatalogRequestDecisionIT}.
 */
@SpringBootTest(
        classes = {
                CategoryRequestService.class,
                PlatformCategoryOrderLookup.class,
                CacheConfig.class,
                CategoryRequestServiceCacheTest.ClockTestConfig.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "app.admin-email=admin@beautica.test"
)
@DisplayName("CategoryRequestService — approved-categories cache behaviour")
class CategoryRequestServiceCacheTest {

    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");

    @TestConfiguration
    static class ClockTestConfig {
        @Bean
        Clock testClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @MockBean PlatformCategoryRepository platformCategoryRepository;
    @MockBean EmailService emailService;
    @MockBean InAppNotificationService inAppNotificationService;
    @MockBean NotificationOutboxService notificationOutboxService;
    @MockBean AdminRequestsPortalLink adminRequestsPortalLink;
    @MockBean ServiceTypeSuggestionService serviceTypeSuggestionService;

    @Autowired CategoryRequestService service;
    @Autowired PlatformCategoryOrderLookup platformCategoryOrderLookup;
    @Autowired CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
        var cache = cacheManager.getCache("approved-categories");
        if (cache != null) cache.clear();
        var orderCache = cacheManager.getCache("platform-category-order");
        if (orderCache != null) orderCache.clear();
    }

    @Test
    @DisplayName("should_hitCache_when_listApprovedCalledTwice")
    void should_hitCache_when_listApprovedCalledTwice() {
        when(platformCategoryRepository.findApprovedActive())
                .thenReturn(List.of(PlatformCategory.ofApproved("MANICURE", "Манікюр")));

        service.listApproved();
        service.listApproved();

        verify(platformCategoryRepository, times(1)).findApprovedActive();
    }

    @Test
    @DisplayName("should_registerNamedCache_when_contextLoads")
    void should_registerNamedCache_when_contextLoads() {
        assertThat(cacheManager.getCache("approved-categories")).isNotNull();
    }

    // ── platform-category-order cache (perf follow-up, Phase 13.6) ─────────────
    //
    // PlatformCategoryOrderLookup#getApprovedActive backs ServiceCatalogService
    // #buildCategoryOrder — this proves the sibling cache (a separate bean/cache from
    // "approved-categories" above) actually hits on a second call.

    @Test
    @DisplayName("should_registerNamedCache_when_contextLoads_platformCategoryOrder")
    void should_registerNamedCache_when_contextLoads_platformCategoryOrder() {
        assertThat(cacheManager.getCache("platform-category-order")).isNotNull();
    }

    @Test
    @DisplayName("should_hitCache_when_platformCategoryOrderLookupCalledTwice")
    void should_hitCache_when_platformCategoryOrderLookupCalledTwice() {
        when(platformCategoryRepository.findApprovedActive())
                .thenReturn(List.of(PlatformCategory.ofApproved("MANICURE", "Манікюр")));

        platformCategoryOrderLookup.getApprovedActive();
        platformCategoryOrderLookup.getApprovedActive();

        verify(platformCategoryRepository, times(1)).findApprovedActive();
    }
}
