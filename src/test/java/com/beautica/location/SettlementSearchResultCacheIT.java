package com.beautica.location;

import com.beautica.AbstractIntegrationTest;
import com.beautica.support.HibernateStatistics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 329 — the per-query {@code settlementSearch} cache, proven at the HTTP boundary.
 *
 * <p>{@code SettlementSearchResultCacheTest} proves the cache against the SERVICE with a mocked
 * repository, so it cannot see the controller, the real proxy chain (cache advisor outside the
 * transaction advisor) or the real SQL. This class drives {@code GET /api/v1/settlements} over real
 * HTTP against the real seeded taxonomy and measures the property that matters in production —
 * JDBC statements issued — with the same {@link HibernateStatistics} probe {@code SettlementSearchIT}
 * uses. A hit must issue zero statements AND return a byte-identical body; a refused term must
 * issue zero statements AND never become a cache key.
 *
 * <p>Read-only against the taxonomy, which {@link AbstractIntegrationTest#cleanDb()} never
 * truncates, so the tests are order-independent.
 */
@DisplayName("Phase 329 settlement search result cache — HTTP-level integration")
class SettlementSearchResultCacheIT extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/settlements";

    /** Restated, not imported, so a rename of the production constant fails here. */
    private static final String SETTLEMENT_SEARCH_CACHE = "settlementSearch";

    private static final String QUERY_TOO_SHORT_MESSAGE = "Введіть щонайменше 3 символи";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    /**
     * The application context (and therefore the cache) is shared with every other
     * {@code AbstractIntegrationTest}; a term cached by an earlier class would turn the first
     * request below into a hit and make the "the miss issues SQL" control fail — or, worse, make a
     * zero-statement assertion pass for the wrong reason.
     */
    @BeforeEach
    void clearSettlementSearchCache() {
        cacheManager.getCache(SETTLEMENT_SEARCH_CACHE).clear();
        statistics = HibernateStatistics.enabledOn(entityManagerFactory);
    }

    /** Term passed as a URI template variable — a pre-encoded Cyrillic URL is double-encoded. */
    private ResponseEntity<String> get(String query) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<String> response = restTemplate.exchange(
                URL + "?query={q}", HttpMethod.GET, new HttpEntity<>(headers), String.class, query);
        assertThat(response.getStatusCode())
                .as("GET %s?query=%s is permitAll and must answer 200", URL, query)
                .isEqualTo(HttpStatus.OK);
        return response;
    }

    @SuppressWarnings("unchecked")
    private long settlementSearchCacheSize() {
        Cache<Object, Object> nativeCache = (Cache<Object, Object>)
                cacheManager.getCache(SETTLEMENT_SEARCH_CACHE).getNativeCache();
        nativeCache.cleanUp();
        return nativeCache.estimatedSize();
    }

    @Test
    @DisplayName("GET «львів», «львів», «ЛЬВІВ» — only the first request issues SQL; all three bodies are identical")
    void should_serveRepeatAndCaseVariantFromCache_when_sameTermIsRequestedOverHttp()
            throws Exception {
        long beforeMiss = statistics.getPrepareStatementCount();
        String missBody = get("львів").getBody();
        long afterMiss = statistics.getPrepareStatementCount();

        String repeatBody = get("львів").getBody();
        String upperCaseBody = get("ЛЬВІВ").getBody();
        long afterHits = statistics.getPrepareStatementCount();

        assertThat(afterMiss - beforeMiss)
                .as("the first request is a MISS (cache cleared in @BeforeEach) and must execute "
                        + "the ranked query — otherwise the zero below is backed by a probe that "
                        + "counts nothing")
                .isPositive();
        assertThat(afterHits - afterMiss)
                .as("the repeat and the upper-case variant must be answered from settlementSearch "
                        + "without a single JDBC statement — the key is the lower-cased term")
                .isZero();
        JsonNode missData = objectMapper.readTree(missBody).path("data");
        assertThat(missData)
                .as("the miss must return rows, or identical empty bodies prove nothing")
                .isNotEmpty();
        assertThat(missData.get(0).path("nameUk").asText()).isEqualTo("Львів");
        assertThat(repeatBody)
                .as("a cache hit must be byte-identical to the miss that populated it")
                .isEqualTo(missBody);
        assertThat(upperCaseBody)
                .as("«ЛЬВІВ» folds to the «львів» key and must serve the same body")
                .isEqualTo(missBody);
        assertThat(settlementSearchCacheSize())
                .as("three requests, one normalised term — exactly one cache entry")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("GET «İİİ» — 200, empty data, the 3-character hint, zero SQL, and no cache entry")
    void should_refuseWithoutCaching_when_termLosesItsAlphanumericRunOnLowerCasing()
            throws Exception {
        // U+0130 lowers to «i» + U+0307 (a combining mark, not alphanumeric), so the RAW term has a
        // 3-letter run and the lower-cased one has none. Admission runs on the lower-cased string
        // (Phase 329 audit, security MEDIUM), so this must be refused before the cache — a refused
        // term that became a key would let an anonymous caller spend cache slots for free.
        long before = statistics.getPrepareStatementCount();

        JsonNode envelope = objectMapper.readTree(get("İİİ").getBody());

        assertThat(envelope.path("success").asBoolean()).isTrue();
        assertThat(envelope.path("data").isArray())
                .as("refusal is an explicit empty LIST, never null and never the major list")
                .isTrue();
        assertThat(envelope.path("data")).isEmpty();
        assertThat(envelope.path("message").asText()).isEqualTo(QUERY_TOO_SHORT_MESSAGE);
        assertThat(statistics.getPrepareStatementCount() - before)
                .as("a refused term must reach neither the cache loader nor the database")
                .isZero();
        assertThat(settlementSearchCacheSize())
                .as("a refused term must never become a settlementSearch key")
                .isZero();
    }
}
