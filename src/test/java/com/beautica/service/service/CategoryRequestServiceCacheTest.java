package com.beautica.service.service;

import com.beautica.auth.TokenGenerator;
import com.beautica.config.CacheConfig;
import com.beautica.config.PublicBaseUrlProperties;
import com.beautica.notification.EmailService;
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
    @Autowired CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
        var cache = cacheManager.getCache("approved-categories");
        if (cache != null) cache.clear();
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
    @DisplayName("should_evictCache_when_rejectCalled")
    void should_evictCache_when_rejectCalled() {
        when(platformCategoryRepository.findApprovedActive())
                .thenReturn(List.of(PlatformCategory.ofApproved("MANICURE", "Манікюр")));
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(platformCategoryRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(pending()));

        service.listApproved();          // populate cache
        service.reject(RAW_TOKEN);       // must evict
        service.listApproved();          // cache miss → re-query

        verify(platformCategoryRepository, times(2)).findApprovedActive();
    }

    @Test
    @DisplayName("should_registerNamedCache_when_contextLoads")
    void should_registerNamedCache_when_contextLoads() {
        assertThat(cacheManager.getCache("approved-categories")).isNotNull();
    }
}
