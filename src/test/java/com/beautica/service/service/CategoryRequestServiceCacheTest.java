package com.beautica.service.service;

import com.beautica.auth.TokenGenerator;
import com.beautica.config.CacheConfig;
import com.beautica.config.PublicBaseUrlProperties;
import com.beautica.notification.EmailService;
import com.beautica.search.service.SearchSuggestionCatalogue;
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
 * {@code @Cacheable}/{@code @CacheEvict} behaviour for the approved-category read path.
 *
 * <p>Loads only the service-under-test, the real {@link CacheConfig}, and a fixed
 * {@link Clock}; all collaborators are mocks. Proves PERF-MEDIUM: repeat reads of
 * {@code listApproved} hit the {@code approved-categories} cache (one repository query),
 * and an {@code approve}/{@code reject} decision invalidates the cache so the next read
 * re-queries — all within one JVM, no DB.
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
    private static final String RAW_TOKEN = "raw-token-value";
    private static final String TOKEN_HASH = "deadbeefcafebabe";

    @TestConfiguration
    static class ClockTestConfig {
        @Bean
        Clock testClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @MockBean PlatformCategoryRepository platformCategoryRepository;
    @MockBean TokenGenerator tokenGenerator;
    @MockBean EmailService emailService;
    @MockBean PublicBaseUrlProperties publicBaseUrlProperties;
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

    private PlatformCategory pending() {
        return PlatformCategory.ofPendingRequest(
                "NAIL_ART", "Нейл-арт", null, TOKEN_HASH,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC), null);
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
    @DisplayName("should_evictCache_when_approveCalled")
    void should_evictCache_when_approveCalled() {
        when(platformCategoryRepository.findApprovedActive())
                .thenReturn(List.of(PlatformCategory.ofApproved("MANICURE", "Манікюр")));
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(platformCategoryRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(pending()));

        service.listApproved();          // populate cache
        service.approve(RAW_TOKEN);      // must evict
        service.listApproved();          // cache miss → re-query

        verify(platformCategoryRepository, times(2)).findApprovedActive();
    }

    @Test
    @DisplayName("should_notEvictCache_when_rejectCalled")
    void should_notEvictCache_when_rejectCalled() {
        when(platformCategoryRepository.findApprovedActive())
                .thenReturn(List.of(PlatformCategory.ofApproved("MANICURE", "Манікюр")));
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(platformCategoryRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(pending()));

        service.listApproved();          // populate cache
        service.reject(RAW_TOKEN);       // changes no cached data — must NOT evict
        service.listApproved();          // still cached

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
    // "approved-categories" above) actually hits on a second call, and that approve/reject
    // evict it via the same @Caching(evict = {...}) block that evicts "approved-categories".

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

    @Test
    @DisplayName("should_evictPlatformCategoryOrderCache_when_approveCalled")
    void should_evictPlatformCategoryOrderCache_when_approveCalled() {
        when(platformCategoryRepository.findApprovedActive())
                .thenReturn(List.of(PlatformCategory.ofApproved("MANICURE", "Манікюр")));
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(platformCategoryRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(pending()));

        platformCategoryOrderLookup.getApprovedActive();  // populate cache
        service.approve(RAW_TOKEN);                       // must evict
        platformCategoryOrderLookup.getApprovedActive();  // cache miss → re-query

        verify(platformCategoryRepository, times(2)).findApprovedActive();
    }

    @Test
    @DisplayName("should_notEvictPlatformCategoryOrderCache_when_rejectCalled")
    void should_notEvictPlatformCategoryOrderCache_when_rejectCalled() {
        when(platformCategoryRepository.findApprovedActive())
                .thenReturn(List.of(PlatformCategory.ofApproved("MANICURE", "Манікюр")));
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(platformCategoryRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(pending()));

        platformCategoryOrderLookup.getApprovedActive();  // populate cache
        service.reject(RAW_TOKEN);                        // must NOT evict
        platformCategoryOrderLookup.getApprovedActive();  // still cached

        verify(platformCategoryRepository, times(1)).findApprovedActive();
    }

    // ── searchSuggestionCatalogue cache: category approve/reject change the selectable labels ──

    @Test
    @DisplayName("should_evictSuggestionCatalogue_when_approveCalled")
    void should_evictSuggestionCatalogue_when_approveCalled() {
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(platformCategoryRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(pending()));
        cacheManager.getCache(SearchSuggestionCatalogue.CACHE_NAME).put("k", "stale");

        service.approve(RAW_TOKEN);

        assertThat(cacheManager.getCache(SearchSuggestionCatalogue.CACHE_NAME).get("k")).isNull();
    }

    @Test
    @DisplayName("should_notEvictSuggestionCatalogue_when_rejectCalled")
    void should_notEvictSuggestionCatalogue_when_rejectCalled() {
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(platformCategoryRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(pending()));
        cacheManager.getCache(SearchSuggestionCatalogue.CACHE_NAME).put("k", "stale");

        service.reject(RAW_TOKEN);

        assertThat(cacheManager.getCache(SearchSuggestionCatalogue.CACHE_NAME).get("k")).isNotNull();
    }

    // ── no-op outcomes must not flush the caches (public, unthrottled-by-token endpoint) ──

    private void warmAllCaches() {
        cacheManager.getCache("approved-categories").put("all", "warm");
        cacheManager.getCache("platform-category-order").put("all", "warm");
        cacheManager.getCache(SearchSuggestionCatalogue.CACHE_NAME).put("k", "warm");
    }

    private void assertAllCachesWarm() {
        assertThat(cacheManager.getCache("approved-categories").get("all")).isNotNull();
        assertThat(cacheManager.getCache("platform-category-order").get("all")).isNotNull();
        assertThat(cacheManager.getCache(SearchSuggestionCatalogue.CACHE_NAME).get("k")).isNotNull();
    }

    @Test
    @DisplayName("should_notEvictCatalogue_when_tokenUnknown")
    void should_notEvictCatalogue_when_tokenUnknown() {
        when(tokenGenerator.hash("garbage")).thenReturn("unknownhash");
        when(platformCategoryRepository.findByTokenHash("unknownhash")).thenReturn(Optional.empty());
        warmAllCaches();

        var outcome = service.approve("garbage");

        assertThat(outcome).isEqualTo(CategoryRequestService.DecisionOutcome.ALREADY_DECIDED);
        assertAllCachesWarm();
    }

    @Test
    @DisplayName("should_notEvictCatalogue_when_alreadyDecided")
    void should_notEvictCatalogue_when_alreadyDecided() {
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        PlatformCategory decided = pending();
        decided.approve(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(platformCategoryRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(decided));
        warmAllCaches();

        var outcome = service.approve(RAW_TOKEN);

        assertThat(outcome).isEqualTo(CategoryRequestService.DecisionOutcome.ALREADY_DECIDED);
        assertAllCachesWarm();
    }

    @Test
    @DisplayName("should_notEvictCatalogue_when_tokenExpired")
    void should_notEvictCatalogue_when_tokenExpired() {
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        PlatformCategory expired = PlatformCategory.ofPendingRequest(
                "NAIL_ART", "Нейл-арт", null, TOKEN_HASH,
                OffsetDateTime.ofInstant(NOW.minusSeconds(7200), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.minusSeconds(3600), ZoneOffset.UTC), null);
        when(platformCategoryRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(expired));
        warmAllCaches();

        var outcome = service.approve(RAW_TOKEN);

        assertThat(outcome).isEqualTo(CategoryRequestService.DecisionOutcome.INVALID_OR_EXPIRED);
        assertAllCachesWarm();
    }
}
