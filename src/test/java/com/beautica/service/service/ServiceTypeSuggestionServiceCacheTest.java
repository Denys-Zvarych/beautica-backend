package com.beautica.service.service;

import com.beautica.auth.TokenGenerator;
import com.beautica.config.CacheConfig;
import com.beautica.config.PublicBaseUrlProperties;
import com.beautica.notification.EmailService;
import com.beautica.search.service.SearchSuggestionCatalogue;
import com.beautica.service.entity.ServiceTypeSuggestion;
import com.beautica.service.repository.ServiceTypeSuggestionRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * Catalogue-cache eviction on service-type suggestion decisions: approve materializes a
 * service type, so every cache derived from service types must be evicted; reject changes
 * no catalogue rows, so those caches must stay populated.
 */
@SpringBootTest(
        classes = {
                ServiceTypeSuggestionService.class,
                CacheConfig.class,
                ServiceTypeSuggestionServiceCacheTest.ClockTestConfig.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "app.admin-email=admin@beautica.test"
)
@DisplayName("ServiceTypeSuggestionService — catalogue cache eviction")
class ServiceTypeSuggestionServiceCacheTest {

    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
    private static final String RAW_TOKEN = "raw-token-value";
    private static final String TOKEN_HASH = "deadbeefcafebabe";
    private static final List<String> CACHES = List.of(
            "service-types", "service-type-search", SearchSuggestionCatalogue.CACHE_NAME);

    @TestConfiguration
    @EnableTransactionManagement   // real transaction advisor (LOWEST_PRECEDENCE) so ordering vs the cache advisor is exercised
    static class ClockTestConfig {
        @Bean
        Clock testClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @MockBean ServiceTypeSuggestionRepository suggestionRepository;
    @MockBean TokenGenerator tokenGenerator;
    @MockBean EmailService emailService;
    @MockBean PublicBaseUrlProperties publicBaseUrlProperties;
    @MockBean ServiceTypePromotionService promotionService;
    @MockBean PlatformTransactionManager transactionManager;

    @Autowired ServiceTypeSuggestionService service;
    @Autowired CacheManager cacheManager;

    @BeforeEach
    void warmCaches() {
        for (String name : CACHES) {
            cache(name).clear();
            cache(name).put("k", "stale");
        }
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(suggestionRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(pending()));
    }

    private Cache cache(String name) {
        Cache cache = cacheManager.getCache(name);
        assertThat(cache).as("cache %s registered", name).isNotNull();
        return cache;
    }

    private ServiceTypeSuggestion pending() {
        return ServiceTypeSuggestion.ofPending(
                "HAIR", "Балаяж", "Опис", UUID.randomUUID(), TOKEN_HASH,
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("should_evictAllCatalogueCaches_when_approveCalled")
    void should_evictAllCatalogueCaches_when_approveCalled() {
        service.approve(RAW_TOKEN);

        for (String name : CACHES) {
            assertThat(cache(name).get("k")).as("%s evicted", name).isNull();
        }
    }

    @Test
    @DisplayName("should_keepCatalogueCachesPopulated_when_rejectCalled")
    void should_keepCatalogueCachesPopulated_when_rejectCalled() {
        service.reject(RAW_TOKEN);

        for (String name : CACHES) {
            assertThat(cache(name).get("k")).as("%s kept", name).isNotNull();
        }
    }

    @Test
    @DisplayName("should_keepCatalogueCachesPopulated_when_approveFails")
    void should_keepCatalogueCachesPopulated_when_approveFails() {
        doThrow(new IllegalStateException("promotion failed")).when(promotionService).promote(org.mockito.ArgumentMatchers.any());

        try {
            service.approve(RAW_TOKEN);
        } catch (IllegalStateException expected) {
            // eviction must not run on failure
        }

        for (String name : CACHES) {
            assertThat(cache(name).get("k")).as("%s kept", name).isNotNull();
        }
    }

    @Test
    @DisplayName("should_notEvictCatalogue_when_tokenUnknown")
    void should_notEvictCatalogue_when_tokenUnknown() {
        when(tokenGenerator.hash("garbage")).thenReturn("unknownhash");
        when(suggestionRepository.findByTokenHash("unknownhash")).thenReturn(Optional.empty());

        var outcome = service.approve("garbage");

        assertThat(outcome).isEqualTo(ServiceTypeSuggestionService.DecisionOutcome.ALREADY_DECIDED);
        for (String name : CACHES) {
            assertThat(cache(name).get("k")).as("%s kept", name).isNotNull();
        }
    }

    @Test
    @DisplayName("should_notEvictCatalogue_when_alreadyDecided")
    void should_notEvictCatalogue_when_alreadyDecided() {
        ServiceTypeSuggestion decided = pending();
        decided.approve(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(suggestionRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(decided));

        var outcome = service.approve(RAW_TOKEN);

        assertThat(outcome).isEqualTo(ServiceTypeSuggestionService.DecisionOutcome.ALREADY_DECIDED);
        for (String name : CACHES) {
            assertThat(cache(name).get("k")).as("%s kept", name).isNotNull();
        }
    }

    @Test
    @DisplayName("should_notEvictCatalogue_when_tokenExpired")
    void should_notEvictCatalogue_when_tokenExpired() {
        ServiceTypeSuggestion expired = ServiceTypeSuggestion.ofPending(
                "HAIR", "Балаяж", "Опис", UUID.randomUUID(), TOKEN_HASH,
                OffsetDateTime.ofInstant(NOW.minusSeconds(3600), ZoneOffset.UTC));
        when(suggestionRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(expired));

        var outcome = service.approve(RAW_TOKEN);

        assertThat(outcome).isEqualTo(ServiceTypeSuggestionService.DecisionOutcome.INVALID_OR_EXPIRED);
        for (String name : CACHES) {
            assertThat(cache(name).get("k")).as("%s kept", name).isNotNull();
        }
    }

    @Test
    @DisplayName("should_evictOnlyAfterCommit_when_approveCalled")
    void should_evictOnlyAfterCommit_when_approveCalled() {
        List<Boolean> populatedAtCommit = new java.util.ArrayList<>();
        doAnswer(inv -> {
            for (String name : CACHES) {
                populatedAtCommit.add(cache(name).get("k") != null);
            }
            return null;
        }).when(transactionManager).commit(any());

        service.approve(RAW_TOKEN);

        verify(transactionManager).commit(any());
        assertThat(populatedAtCommit)
                .as("caches still populated when the transaction commits (eviction is not pre-commit)")
                .containsOnly(true).hasSize(CACHES.size());
        for (String name : CACHES) {
            assertThat(cache(name).get("k")).as("%s evicted after commit", name).isNull();
        }
    }

    @Test
    @DisplayName("should_evictNothing_when_promoteThrowsAndTransactionRollsBack")
    void should_evictNothing_when_promoteThrowsAndTransactionRollsBack() {
        doThrow(new IllegalStateException("promotion failed")).when(promotionService).promote(any());

        try {
            service.approve(RAW_TOKEN);
        } catch (IllegalStateException expected) {
            // rollback path
        }

        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        for (String name : CACHES) {
            assertThat(cache(name).get("k")).as("%s kept after rollback", name).isNotNull();
        }
    }
}
