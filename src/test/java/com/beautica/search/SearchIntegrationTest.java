package com.beautica.search;

import com.beautica.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack integration coverage for {@code GET /api/v1/search/{masters,salons}}.
 *
 * <p>Drives the real {@code SearchService} → native SQL → Postgres pipeline so
 * the {@code GROUP BY ... HAVING} count contract is exercised end-to-end. The
 * carryover scenarios (active-flag exclusions, HAVING-aware count) are the
 * regression net flagged by Phase 6.3 QA in {@code docs/backlog.md}.</p>
 *
 * <p><b>Why no auth helpers:</b> the endpoints under test are
 * {@code permitAll()} per {@code SecurityConfig}. Tests issue raw GETs without
 * bearer tokens — that's the feature. The single {@code _withNoAuth_} test
 * documents that coverage explicitly.</p>
 */
@DisplayName("Search — full-flow integration")
class SearchIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SearchIntegrationTest.class);
    private static final String MASTERS_URL = "/api/v1/search/masters";
    private static final String SALONS_URL = "/api/v1/search/salons";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CacheManager cacheManager;

    /**
     * Wires the Apache HttpClient factory before the first request in each
     * test. Kept as an explicit method (called once per test) rather than a
     * {@code @BeforeEach} hook to avoid swapping the factory between
     * unrelated tests in the same JVM-shared application context. The factory
     * itself is reusable; only the call ordering matters.
     */
    private void ensureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    // ── Master search — happy paths ──────────────────────────────────────────

    @Test
    @DisplayName("GET /search/masters — finds masters by city when masters exist in city")
    void should_findMastersByCity_when_mastersExistInCity() throws Exception {
        ensureHttpClient();
        seedMasterWithCity("Київ", "4.50");
        seedMasterWithCity("Київ", "4.20");
        seedMasterWithCity("Київ", "3.80");
        seedMasterWithCity("Харків", "4.90");

        log.debug("Act: GET {}?city=Київ — must return 3 masters", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?city=Київ&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.path("data").path("data").size())
                .as("only Київ masters should be returned")
                .isEqualTo(3);
        assertThat(root.path("data").path("totalElements").asLong()).isEqualTo(3);
    }

    @Test
    @DisplayName("GET /search/masters — filters by category when master has service in category")
    void should_filterByCategory_when_masterHasServiceInCategory() throws Exception {
        ensureHttpClient();
        UUID m1 = seedMaster("Київ", "4.00");
        UUID salon1 = jdbcTemplate.queryForObject("SELECT salon_id FROM masters WHERE id = ?", UUID.class, m1);
        seedServiceWithCategory(m1, salon1, "MANICURE", new BigDecimal("250.00"), true, true);

        UUID m2 = seedMaster("Київ", "4.00");
        UUID salon2 = jdbcTemplate.queryForObject("SELECT salon_id FROM masters WHERE id = ?", UUID.class, m2);
        seedServiceWithCategory(m2, salon2, "HAIRCUT", new BigDecimal("400.00"), true, true);

        log.debug("Act: GET {}?category=MANICURE — must return 1 master", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?category=MANICURE&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("data").size()).isEqualTo(1);
        assertThat(data.path("data").get(0).path("masterId").asText()).isEqualTo(m1.toString());
    }

    @Test
    @DisplayName("GET /search/masters — filters by price range using HAVING aggregate")
    void should_filterByPriceRange_when_minAndMaxPriceSet() throws Exception {
        ensureHttpClient();
        seedMasterWithService("Київ", "4.00", new BigDecimal("150.00"));
        seedMasterWithService("Київ", "4.00", new BigDecimal("300.00"));
        seedMasterWithService("Київ", "4.00", new BigDecimal("600.00"));

        log.debug("Act: GET {}?minPrice=200&maxPrice=500 — only 300 in range", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?minPrice=200&maxPrice=500&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("data").size()).isEqualTo(1);
        assertThat(data.path("totalElements").asLong())
                .as("HAVING-aware count must match the filtered page size")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("GET /search/masters — filters by minimum rating")
    void should_filterByMinRating_when_ratingSet() throws Exception {
        ensureHttpClient();
        seedMasterWithCity("Київ", "3.50");
        seedMasterWithCity("Київ", "4.00");
        seedMasterWithCity("Київ", "4.80");

        log.debug("Act: GET {}?minRating=4.0 — must return 2 masters", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?minRating=4.0&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("data").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("GET /search/masters — totalElements correct when many masters match a filter and page is small")
    void should_returnCorrectTotalElements_when_manyMastersMatchFilter() throws Exception {
        ensureHttpClient();
        for (int i = 0; i < 7; i++) {
            seedMasterWithCity("Київ", "4.00");
        }

        log.debug("Act: GET {}?city=Київ&size=3 — must return 3 in content, totalElements=7", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?city=Київ&page=0&size=3", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("data").size()).isEqualTo(3);
        assertThat(data.path("totalElements").asLong()).isEqualTo(7L);
        assertThat(data.path("totalPages").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("GET /search/masters — empty content when city has no masters")
    void should_returnEmptyList_when_noCityMatch() throws Exception {
        ensureHttpClient();
        seedMasterWithCity("Київ", "4.00");

        log.debug("Act: GET {}?city=Одеса — must return 0 masters", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?city=Одеса&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("data").isArray()).isTrue();
        assertThat(data.path("data").size()).isEqualTo(0);
        assertThat(data.path("totalElements").asLong()).isEqualTo(0L);
    }

    // ── Public / no-auth coverage ────────────────────────────────────────────

    @Test
    @DisplayName("GET /search/{masters,salons} — both endpoints return 200 without auth")
    void should_return200WithNoAuth_when_searchEndpointsAccessed() {
        ensureHttpClient();

        ResponseEntity<String> mastersResponse = restTemplate.exchange(
                MASTERS_URL + "?page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);
        ResponseEntity<String> salonsResponse = restTemplate.exchange(
                SALONS_URL + "?page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(mastersResponse.getStatusCode())
                .as("masters search must be reachable without bearer token")
                .isEqualTo(HttpStatus.OK);
        assertThat(salonsResponse.getStatusCode())
                .as("salons search must be reachable without bearer token")
                .isEqualTo(HttpStatus.OK);
    }

    // ── Carryover from Phase 6.3 (deferred IT coverage) ──────────────────────

    @Test
    @DisplayName("GET /search/masters — excludes masters with masters.is_active=false")
    void should_excludeInactiveMasters_when_searchPerformed() throws Exception {
        ensureHttpClient();
        UUID activeId = seedMasterWithCity("Київ", "4.00");
        UUID inactiveId = seedMasterWithCity("Київ", "4.00");
        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", inactiveId);

        log.debug("Act: GET {}?city=Київ — inactive master must NOT appear", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?city=Київ&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode rows = objectMapper.readTree(response.getBody()).path("data").path("data");
        assertThat(rows.size()).isEqualTo(1);
        assertThat(rows.get(0).path("masterId").asText()).isEqualTo(activeId.toString());
    }

    @Test
    @DisplayName("GET /search/masters — excludes masters whose user has users.is_active=false")
    void should_excludeInactiveUsers_when_searchPerformed() throws Exception {
        ensureHttpClient();
        UUID active = seedMasterWithCity("Київ", "4.00");
        UUID inactive = seedMasterWithCity("Київ", "4.00");
        UUID inactiveUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, inactive);
        jdbcTemplate.update("UPDATE users SET is_active = false WHERE id = ?", inactiveUserId);

        log.debug("Act: GET {}?city=Київ — master with inactive user must NOT appear", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?city=Київ&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode rows = objectMapper.readTree(response.getBody()).path("data").path("data");
        assertThat(rows.size()).isEqualTo(1);
        assertThat(rows.get(0).path("masterId").asText()).isEqualTo(active.toString());
    }

    @Test
    @DisplayName("GET /search/masters — price aggregate ignores master_services rows where is_active=false")
    void should_excludeInactiveMasterServices_when_priceFiltered() throws Exception {
        ensureHttpClient();
        UUID master = seedMaster("Київ", "4.00");
        UUID salon = jdbcTemplate.queryForObject("SELECT salon_id FROM masters WHERE id = ?", UUID.class, master);

        // Active row: price 600 (above filter ceiling)
        seedServiceWithCategory(master, salon, "MANICURE", new BigDecimal("600.00"), true, true);
        // Inactive row: price 250 (would be in range if it counted)
        seedServiceWithCategory(master, salon, "MANICURE", new BigDecimal("250.00"), true, false);

        log.debug("Act: GET {}?minPrice=200&maxPrice=300 — inactive master_services must NOT contribute to MIN aggregate", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?minPrice=200&maxPrice=300&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("data").size())
                .as("master with only an inactive 250.00 service must not appear in [200,300] price filter")
                .isEqualTo(0);
        assertThat(data.path("totalElements").asLong()).isEqualTo(0L);
    }

    @Test
    @DisplayName("GET /search/masters — totalElements matches HAVING-filtered count, not pre-HAVING DISTINCT")
    void should_returnCorrectTotalElements_with_priceHavingFilter() throws Exception {
        ensureHttpClient();
        // 1 master in [200,500], 2 outside — naive COUNT(DISTINCT m.id) without HAVING would
        // return 3 and produce phantom pages. Real count must be 1.
        seedMasterWithService("Київ", "4.00", new BigDecimal("100.00"));
        seedMasterWithService("Київ", "4.00", new BigDecimal("350.00"));
        seedMasterWithService("Київ", "4.00", new BigDecimal("700.00"));

        log.debug("Act: GET {}?minPrice=200&maxPrice=500 — totalElements must be 1, not 3", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?minPrice=200&maxPrice=500&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("data").size()).isEqualTo(1);
        assertThat(data.path("totalElements").asLong())
                .as("HAVING-aware count must reflect actual filtered rows")
                .isEqualTo(1L);
    }

    // ── Phase 6.5 — dynamic SQL branches ─────────────────────────────────────

    @Test
    @DisplayName("GET /search/masters — no category/price filter returns null minEffectivePrice (JOIN elided)")
    void should_returnNullMinEffectivePrice_when_noServiceJoinNeeded() throws Exception {
        ensureHttpClient();
        UUID masterId = seedMasterWithService("Київ", "4.50", new BigDecimal("250.00"));

        log.debug("Act: GET {}?city=Київ — no category/price filter, JOIN should be elided", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?city=Київ&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode rows = objectMapper.readTree(response.getBody()).path("data").path("data");
        assertThat(rows.size()).isEqualTo(1);
        assertThat(rows.get(0).path("masterId").asText()).isEqualTo(masterId.toString());
        assertThat(rows.get(0).path("minEffectivePrice").isNull())
                .as("JOIN is elided when no category/price filter — minEffectivePrice column projects NULL")
                .isTrue();
    }

    @Test
    @DisplayName("GET /search/masters — category filter forces JOIN and returns aggregated minEffectivePrice")
    void should_returnAggregatedMinEffectivePrice_when_categoryFilterSet() throws Exception {
        ensureHttpClient();
        UUID master = seedMaster("Київ", "4.20");
        UUID salon = jdbcTemplate.queryForObject("SELECT salon_id FROM masters WHERE id = ?", UUID.class, master);
        seedServiceWithCategory(master, salon, "MANICURE", new BigDecimal("180.00"), true, true);
        seedServiceWithCategory(master, salon, "MANICURE", new BigDecimal("320.00"), true, true);

        log.debug("Act: GET {}?category=MANICURE — JOIN must be active, MIN must return 180.00", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?category=MANICURE&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode rows = objectMapper.readTree(response.getBody()).path("data").path("data");
        assertThat(rows.size()).isEqualTo(1);
        assertThat(new BigDecimal(rows.get(0).path("minEffectivePrice").asText()))
                .as("MIN(COALESCE(price_override, base_price)) over the two active services")
                .isEqualByComparingTo(new BigDecimal("180.00"));
    }

    @Test
    @DisplayName("GET /search/masters — flat count branch returns totalElements without HAVING when no price filter")
    void should_useFlatCountBranch_when_noPriceFilterApplied() throws Exception {
        ensureHttpClient();
        for (int i = 0; i < 5; i++) {
            seedMasterWithCity("Київ", "4.00");
        }

        log.debug("Act: GET {}?city=Київ&size=2 — flat count branch — totalElements must equal 5", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?city=Київ&page=0&size=2", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("data").size()).isEqualTo(2);
        assertThat(data.path("totalElements").asLong())
                .as("flat COUNT(DISTINCT m.id) must count every active master in Київ")
                .isEqualTo(5L);
    }

    @Test
    @DisplayName("GET /search/masters — wrapped-subquery count branch honours HAVING price filter")
    void should_useSubqueryCountBranch_when_priceFilterApplied() throws Exception {
        ensureHttpClient();
        // Five candidates; price filter should retain exactly two.
        seedMasterWithService("Київ", "4.00", new BigDecimal("100.00"));   // below
        seedMasterWithService("Київ", "4.00", new BigDecimal("250.00"));   // in range
        seedMasterWithService("Київ", "4.00", new BigDecimal("400.00"));   // in range
        seedMasterWithService("Київ", "4.00", new BigDecimal("700.00"));   // above
        seedMasterWithService("Київ", "4.00", new BigDecimal("900.00"));   // above

        log.debug("Act: GET {}?minPrice=200&maxPrice=500 — subquery count branch — totalElements must equal 2", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?minPrice=200&maxPrice=500&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("data").size()).isEqualTo(2);
        assertThat(data.path("totalElements").asLong())
                .as("subquery wrapper must respect HAVING — exactly 2 masters fall inside [200,500]")
                .isEqualTo(2L);
    }

    // ── Phase 6.5 — caching ──────────────────────────────────────────────────

    @Test
    @DisplayName("GET /search/masters — first 5 pages populate the search:masters cache")
    void should_populateCache_when_firstPagesQueried() {
        ensureHttpClient();
        Cache cache = cacheManager.getCache("search:masters");
        assertThat(cache).as("search:masters cache must be registered").isNotNull();
        cache.clear();

        seedMasterWithCity("Київ", "4.00");

        log.debug("Act: GET {}?city=Київ — expect cache to be populated after the call", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?city=Київ&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        long cachedEntries = countNativeEntries(cache);
        assertThat(cachedEntries)
                .as("first call must populate exactly one entry in search:masters")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("GET /search/masters — page index >= 5 skips the cache (cold-path)")
    void should_skipCache_when_pageIndexIsAtOrAboveFive() {
        ensureHttpClient();
        seedMasterWithCity("Київ", "4.00");

        Cache cache = cacheManager.getCache("search:masters");
        assertThat(cache).isNotNull();
        cache.clear();
        long before = countNativeEntries(cache);

        // Page 5 (0-indexed) — the @Cacheable condition is `pageNumber < 5`, so
        // this call must bypass the cache. With size=1 and 1 seeded master, the
        // service returns an empty content list but the underlying query still
        // runs.
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?city=Київ&page=5&size=1", HttpMethod.GET,
                anonymous(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        long after = countNativeEntries(cache);
        assertThat(after)
                .as("page=5 is above the cache window; no entry must be written")
                .isEqualTo(before);
    }

    /**
     * Caffeine cache size probe — {@link Cache#getNativeCache()} surfaces the
     * underlying Caffeine cache, which exposes an estimated entry count. The
     * estimate is exact in single-threaded test code immediately after a
     * synchronous call.
     */
    private long countNativeEntries(Cache cache) {
        Object native_ = cache.getNativeCache();
        if (native_ instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> caffeine) {
            return caffeine.estimatedSize();
        }
        throw new IllegalStateException("Expected Caffeine cache, got " + native_.getClass());
    }

    // ── Phase 6.5 QA — combined filters, salons, missing services, cache keys ─

    @Test
    @DisplayName("GET /search/masters — combines all filters and returns only the matching master")
    void should_combineAllFiltersAndReturnOnlyMatchingMaster_when_fullFilterRequest() throws Exception {
        ensureHttpClient();
        // m1: matches every predicate
        UUID m1 = seedMasterWithServiceCategoryAndPrice("Київ", "4.50", "MANICURE", new BigDecimal("180.00"));
        // m2: out of price range — fails minPrice
        seedMasterWithServiceCategoryAndPrice("Київ", "4.50", "MANICURE", new BigDecimal("50.00"));
        // m3: wrong category
        seedMasterWithServiceCategoryAndPrice("Київ", "4.50", "HAIRCUT", new BigDecimal("200.00"));
        // m4: rating below threshold
        seedMasterWithServiceCategoryAndPrice("Київ", "3.00", "MANICURE", new BigDecimal("180.00"));

        log.debug("Act: GET {} with full filter set — must return only m1", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?city=Київ&category=MANICURE&minPrice=100.00&maxPrice=300.00&minRating=4.0"
                        + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode page = objectMapper.readTree(response.getBody()).path("data");
        assertThat(page.path("totalElements").asLong())
                .as("only the fully-conforming master must be counted")
                .isEqualTo(1L);
        assertThat(page.path("data").size()).isEqualTo(1);
        assertThat(page.path("data").get(0).path("masterId").asText()).isEqualTo(m1.toString());
    }

    @Test
    @DisplayName("GET /search/masters — second identical call hits cache without growing entry count")
    void should_returnCachedResultWithoutHittingDb_when_secondIdenticalCall() throws Exception {
        ensureHttpClient();
        Cache cache = cacheManager.getCache("search:masters");
        assertThat(cache).as("search:masters cache must be registered").isNotNull();
        cache.clear();

        seedMasterWithCity("Київ", "4.00");

        String url = MASTERS_URL + "?city=Київ&page=0&size=20";

        // First call: cache miss → populates entry
        ResponseEntity<String> first = restTemplate.exchange(url, HttpMethod.GET, anonymous(), String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countNativeEntries(cache))
                .as("first call must populate exactly one cache entry")
                .isEqualTo(1L);

        // Second identical call: cache hit → no new entry, identical body
        ResponseEntity<String> second = restTemplate.exchange(url, HttpMethod.GET, anonymous(), String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countNativeEntries(cache))
                .as("second identical call must reuse the same cache entry")
                .isEqualTo(1L);

        // Bodies must be byte-identical when cache is hit
        assertThat(objectMapper.readTree(second.getBody()))
                .as("cached body must match the original")
                .isEqualTo(objectMapper.readTree(first.getBody()));
    }

    @Test
    @DisplayName("GET /search/masters — distinct cache keys produced when requests differ by a single filter")
    void should_useDistinctCacheKeys_when_requestsDifferByOnlyOneFilter() {
        ensureHttpClient();
        Cache cache = cacheManager.getCache("search:masters");
        assertThat(cache).as("search:masters cache must be registered").isNotNull();
        cache.clear();

        seedMasterWithCity("Київ", "4.00");
        seedMasterWithCity("Львів", "4.00");

        restTemplate.exchange(MASTERS_URL + "?city=Київ&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);
        restTemplate.exchange(MASTERS_URL + "?city=Львів&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        assertThat(countNativeEntries(cache))
                .as("each distinct filter combo must occupy its own cache entry")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("GET /search/masters — master without master_services rows surfaces with null minEffectivePrice when no filter applied")
    void should_returnMasterWithNullPrice_when_masterHasNoMasterServicesRows_andNoFilter() throws Exception {
        ensureHttpClient();
        UUID masterId = seedMasterWithoutServices("Київ", "4.00");

        log.debug("Act: GET {}?city=Київ — JOIN must be elided, master visible with null price", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?city=Київ&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode page = objectMapper.readTree(response.getBody()).path("data");
        assertThat(page.path("totalElements").asLong()).isEqualTo(1L);
        assertThat(page.path("data").get(0).path("masterId").asText()).isEqualTo(masterId.toString());
        assertThat(page.path("data").get(0).path("minEffectivePrice").isNull())
                .as("no service-join branch → minEffectivePrice must project NULL")
                .isTrue();
    }

    @Test
    @DisplayName("GET /search/masters — master without services excluded when category filter forces the JOIN")
    void should_excludeMasterWithoutServices_when_categoryFilterApplied() throws Exception {
        ensureHttpClient();
        seedMasterWithoutServices("Київ", "4.00");

        log.debug("Act: GET {}?city=Київ&category=MANICURE — JOIN active, master without services must drop", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?city=Київ&category=MANICURE&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode page = objectMapper.readTree(response.getBody()).path("data");
        assertThat(page.path("totalElements").asLong())
                .as("category filter forces JOIN to master_services — master without rows must not appear")
                .isZero();
        assertThat(page.path("data").size()).isZero();
    }

    // ── Phase 6.5 QA — salon search IT parity ────────────────────────────────

    @Test
    @DisplayName("GET /search/salons — finds salons by city when salons exist in city")
    void should_findSalonsByCity_when_salonsExistInCity() throws Exception {
        ensureHttpClient();
        seedActiveSalon("Київ", "Region-A");
        seedActiveSalon("Львів", "Region-B");

        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL + "?city=Київ&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode page = objectMapper.readTree(response.getBody()).path("data");
        assertThat(page.path("totalElements").asLong()).isEqualTo(1L);
        assertThat(page.path("data").get(0).path("city").asText()).isEqualTo("Київ");
    }

    @Test
    @DisplayName("GET /search/salons — empty page when no salons match city")
    void should_returnEmptyList_when_noSalonsMatchCity() throws Exception {
        ensureHttpClient();
        seedActiveSalon("Київ", null);

        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL + "?city=Одеса&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(response.getBody())
                .path("data").path("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("GET /search/salons — first 5 pages populate the search:salons cache")
    void should_populateSalonCache_when_firstPagesQueried() {
        ensureHttpClient();
        Cache cache = cacheManager.getCache("search:salons");
        assertThat(cache).as("search:salons cache must be registered").isNotNull();
        cache.clear();

        seedActiveSalon("Київ", null);

        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL + "?city=Київ&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(countNativeEntries(cache))
                .as("first salon search call must populate exactly one entry")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("GET /search/salons — page index >= 5 skips the search:salons cache")
    void should_skipSalonCache_when_pageIndexIsAtOrAboveFive() {
        ensureHttpClient();
        Cache cache = cacheManager.getCache("search:salons");
        assertThat(cache).as("search:salons cache must be registered").isNotNull();
        cache.clear();

        seedActiveSalon("Київ", null);

        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL + "?city=Київ&page=5&size=1", HttpMethod.GET,
                anonymous(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(countNativeEntries(cache))
                .as("page=5 is above the cache window — no entry must be written")
                .isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static HttpHeaders anonymousHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    private static org.springframework.http.HttpEntity<Void> anonymous() {
        return new org.springframework.http.HttpEntity<>(anonymousHeaders());
    }

    /**
     * Creates a SALON_OWNER, a salon under that owner, a SALON_MASTER user,
     * and a {@code masters} row — all rows active by default. Returns the
     * masters.id for downstream wiring.
     */
    private UUID seedMaster(String city, String avgRating) {
        UUID ownerId = UUID.randomUUID();
        String ownerEmail = "search-owner-" + UUID.randomUUID() + "@beautica.test";
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'SALON_OWNER', true)",
                ownerId, ownerEmail, "$2a$04$placeholdervaluefortestonlydigest");

        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, city, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + salonId, city);

        UUID masterUserId = UUID.randomUUID();
        String masterEmail = "search-master-" + UUID.randomUUID() + "@beautica.test";
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, city, is_active) " +
                        "VALUES (?, ?, ?, 'SALON_MASTER', ?, ?, true)",
                masterUserId, masterEmail, "$2a$04$placeholdervaluefortestonlydigest", salonId, city);

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'SALON_MASTER', ?::numeric, 1, true, NOW(), NOW())",
                masterId, masterUserId, salonId, avgRating);

        return masterId;
    }

    /** Convenience: seeds a master without any services. */
    private UUID seedMasterWithCity(String city, String avgRating) {
        return seedMaster(city, avgRating);
    }

    /**
     * Seeds a master plus one active SALON-owned MANICURE service definition
     * priced at {@code basePrice}, with an active {@code master_services} row.
     */
    private UUID seedMasterWithService(String city, String avgRating, BigDecimal basePrice) {
        UUID masterId = seedMaster(city, avgRating);
        UUID salonId = jdbcTemplate.queryForObject(
                "SELECT salon_id FROM masters WHERE id = ?", UUID.class, masterId);
        seedServiceWithCategory(masterId, salonId, "MANICURE", basePrice, true, true);
        return masterId;
    }

    /**
     * Seeds a master plus one active SALON-owned service definition with the
     * given category, priced at {@code basePrice}, and an active
     * {@code master_services} row. Combines the existing {@code seedMaster} +
     * {@code seedServiceWithCategory} idiom into a single call so the
     * multi-filter combination test can express each seed line as one statement.
     */
    private UUID seedMasterWithServiceCategoryAndPrice(String city, String avgRating,
                                                       String category, BigDecimal basePrice) {
        UUID masterId = seedMaster(city, avgRating);
        UUID salonId = jdbcTemplate.queryForObject(
                "SELECT salon_id FROM masters WHERE id = ?", UUID.class, masterId);
        seedServiceWithCategory(masterId, salonId, category, basePrice, true, true);
        return masterId;
    }

    /**
     * Seeds a master with NO {@code master_services} rows. The underlying
     * {@code seedMaster} already creates an unattached master, so this is a
     * named alias that makes test intent explicit and protects against a
     * future refactor of {@code seedMaster} that starts auto-attaching services.
     */
    private UUID seedMasterWithoutServices(String city, String avgRating) {
        return seedMaster(city, avgRating);
    }

    /**
     * Seeds a SALON_OWNER + an active {@code salons} row in the given city/region.
     * Bypasses the master/user hierarchy because the salon search endpoint only
     * joins {@code salons}; no master or user rows are required.
     */
    private UUID seedActiveSalon(String city, String region) {
        UUID ownerId = UUID.randomUUID();
        String ownerEmail = "salon-owner-" + UUID.randomUUID() + "@beautica.test";
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'SALON_OWNER', true)",
                ownerId, ownerEmail, "$2a$04$placeholdervaluefortestonlydigest");

        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, city, region, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + salonId, city, region);
        return salonId;
    }

    /**
     * Seeds a service definition + master_services row.
     *
     * @param defActive   {@code service_definitions.is_active}
     * @param msActive    {@code master_services.is_active}
     */
    private void seedServiceWithCategory(UUID masterId, UUID salonId, String category,
                                         BigDecimal basePrice, boolean defActive, boolean msActive) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions " +
                        "(id, owner_type, owner_id, name, category, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) " +
                        "VALUES (?, 'SALON', ?, ?, ?, 60, ?, 0, ?, NOW(), NOW())",
                serviceDefId, salonId, "Test Service " + serviceDefId, category, basePrice, defActive);

        UUID msId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, NOW(), NOW())",
                msId, masterId, serviceDefId, msActive);
    }
}
