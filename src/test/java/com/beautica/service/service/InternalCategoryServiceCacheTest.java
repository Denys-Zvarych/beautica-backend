package com.beautica.service.service;

import com.beautica.auth.TokenGenerator;
import com.beautica.common.exception.BusinessException;
import com.beautica.config.CacheConfig;
import com.beautica.config.PublicBaseUrlProperties;
import com.beautica.notification.EmailService;
import com.beautica.search.service.SearchSuggestionCatalogue;
import com.beautica.service.dto.ApprovedCategoryResponse;
import com.beautica.service.dto.CreatePlatformCategoryRequest;
import com.beautica.service.entity.PlatformCategory;
import com.beautica.service.repository.PlatformCategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ops-created categories are APPROVED + active, so create must evict the category caches. */
@SpringBootTest(
        classes = {
                InternalCategoryService.class,
                CategoryRequestService.class,
                CacheConfig.class,
                InternalCategoryServiceCacheTest.ClockTestConfig.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "app.admin-email=admin@beautica.test"
)
@DisplayName("InternalCategoryService — category cache eviction")
class InternalCategoryServiceCacheTest {

    @TestConfiguration
    static class ClockTestConfig {
        @Bean
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-06-01T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    @MockBean PlatformCategoryRepository platformCategoryRepository;
    @MockBean TokenGenerator tokenGenerator;
    @MockBean EmailService emailService;
    @MockBean PublicBaseUrlProperties publicBaseUrlProperties;
    @MockBean ServiceTypeSuggestionService serviceTypeSuggestionService;

    @Autowired InternalCategoryService internalCategoryService;
    @Autowired CategoryRequestService categoryRequestService;
    @Autowired CacheManager cacheManager;

    private final List<PlatformCategory> db = new ArrayList<>();

    @BeforeEach
    void setUp() {
        db.clear();
        db.add(PlatformCategory.ofApproved("MANICURE", "Манікюр"));
        when(platformCategoryRepository.findApprovedActive()).thenAnswer(i -> List.copyOf(db));
        when(platformCategoryRepository.save(any(PlatformCategory.class))).thenAnswer(i -> {
            PlatformCategory c = i.getArgument(0);
            db.add(c);
            return c;
        });
        for (String name : List.of("approved-categories", "platform-category-order",
                SearchSuggestionCatalogue.CACHE_NAME)) {
            cache(name).clear();
        }
    }

    private Cache cache(String name) {
        Cache cache = cacheManager.getCache(name);
        assertThat(cache).isNotNull();
        return cache;
    }

    @Test
    @DisplayName("should_includeNewCategory_when_listApprovedCalledAfterCreate")
    void should_includeNewCategory_when_listApprovedCalledAfterCreate() {
        assertThat(categoryRequestService.listApproved()).hasSize(1); // warm cache

        internalCategoryService.create(new CreatePlatformCategoryRequest("PEDICURE"));

        List<ApprovedCategoryResponse> after = categoryRequestService.listApproved();
        assertThat(after).hasSize(2);
    }

    @Test
    @DisplayName("should_evictOrderAndSuggestionCaches_when_createCalled")
    void should_evictOrderAndSuggestionCaches_when_createCalled() {
        cache("platform-category-order").put("all", "stale");
        cache(SearchSuggestionCatalogue.CACHE_NAME).put("k", "stale");

        internalCategoryService.create(new CreatePlatformCategoryRequest("PEDICURE"));

        assertThat(cache(SearchSuggestionCatalogue.CACHE_NAME).get("k")).isNull();
        assertThat(cache("platform-category-order").get("all")).isNull();
    }

    @Test
    @DisplayName("create with a duplicate name (409) leaves every category cache untouched — eviction runs after invocation only")
    void should_notEvict_when_createFailsWithDuplicateName() {
        List<ApprovedCategoryResponse> warmApproved = categoryRequestService.listApproved();
        Object warmOrder = new Object();
        Object warmSuggestions = new Object();
        cache("platform-category-order").put("all", warmOrder);
        cache(SearchSuggestionCatalogue.CACHE_NAME).put("k", warmSuggestions);
        when(platformCategoryRepository.existsByNameIgnoreCase("MANICURE")).thenReturn(true);

        assertThatThrownBy(() -> internalCategoryService.create(new CreatePlatformCategoryRequest("MANICURE")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Category already exists: MANICURE")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        Cache.ValueWrapper approved = cache("approved-categories").get("all");
        Cache.ValueWrapper order = cache("platform-category-order").get("all");
        Cache.ValueWrapper suggestions = cache(SearchSuggestionCatalogue.CACHE_NAME).get("k");
        assertThat(approved).as("approved-categories entry evicted on failed create").isNotNull();
        assertThat(approved.get()).as("approved-categories cached object identity").isSameAs(warmApproved);
        assertThat(order).as("platform-category-order entry evicted on failed create").isNotNull();
        assertThat(order.get()).as("platform-category-order cached object identity").isSameAs(warmOrder);
        assertThat(suggestions).as("suggestion catalogue entry evicted on failed create").isNotNull();
        assertThat(suggestions.get()).as("suggestion catalogue cached object identity").isSameAs(warmSuggestions);
        verify(platformCategoryRepository, never()).save(any());
    }
}
