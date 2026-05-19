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
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20", HttpMethod.GET,
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
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=3", HttpMethod.GET,
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
                MASTERS_URL + "?location.cityId=" + cityIdByName("Одеса") + "&page=0&size=20", HttpMethod.GET,
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
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20", HttpMethod.GET,
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
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20", HttpMethod.GET,
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
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20", HttpMethod.GET,
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
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=2", HttpMethod.GET,
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
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20", HttpMethod.GET,
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
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=5&size=1", HttpMethod.GET,
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
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&category=MANICURE&minPrice=100.00&maxPrice=300.00&minRating=4.0"
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

        String url = MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20";

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

        restTemplate.exchange(MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);
        restTemplate.exchange(MASTERS_URL + "?location.cityId=" + cityIdByName("Львів") + "&page=0&size=20",
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
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20", HttpMethod.GET,
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
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&category=MANICURE&page=0&size=20", HttpMethod.GET,
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
                SALONS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode page = objectMapper.readTree(response.getBody()).path("data");
        assertThat(page.path("totalElements").asLong()).isEqualTo(1L);
        assertThat(page.path("data").get(0).path("cityLabel").asText()).isEqualTo("Київ");
    }

    @Test
    @DisplayName("GET /search/salons — empty page when no salons match city")
    void should_returnEmptyList_when_noSalonsMatchCity() throws Exception {
        ensureHttpClient();
        seedActiveSalon("Київ", null);

        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + cityIdByName("Одеса") + "&page=0&size=20", HttpMethod.GET,
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
                SALONS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20", HttpMethod.GET,
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
                SALONS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=5&size=1", HttpMethod.GET,
                anonymous(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(countNativeEntries(cache))
                .as("page=5 is above the cache window — no entry must be written")
                .isZero();
    }

    // ── Phase 10.5 — SALON_ADMIN exclusion (Q6 verify-not-present + Q9 cross-role) ─

    @Test
    @DisplayName("GET /search/masters — a SALON_ADMIN account never appears in master results AND is not counted (no pagination leak)")
    void should_excludeSalonAdmin_fromBothResultsAndTotalElements() throws Exception {
        ensureHttpClient();
        UUID legitMaster = seedMasterWithCity("Київ", "4.40");
        UUID adminMasterId = seedSalonAdminMaster("Київ", "4.95");

        log.debug("Act: GET {}?location.cityId=Київ — SALON_ADMIN must be absent from data AND from totalElements", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20", HttpMethod.GET,
                anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("data").size())
                .as("only the non-admin master is discoverable")
                .isEqualTo(1);
        assertThat(data.path("data").get(0).path("masterId").asText())
                .isEqualTo(legitMaster.toString());
        // Q6: assert the admin id is NOT present, not merely that size==1.
        for (JsonNode row : data.path("data")) {
            assertThat(row.path("masterId").asText())
                    .as("the SALON_ADMIN master row must never surface")
                    .isNotEqualTo(adminMasterId.toString());
        }
        // Q9: the predicate must live in the COUNT path too — a leaked admin in
        // totalElements would let an anonymous caller infer admin existence via
        // phantom pages even when the row itself is filtered from the content.
        assertThat(data.path("totalElements").asLong())
                .as("count path excludes SALON_ADMIN — no pagination existence-oracle")
                .isEqualTo(1L);
    }

    // ── Phase 10.5 — district-primary FK filter (read side, end-to-end) ──────

    @Test
    @DisplayName("GET /search/masters — district-primary: ?location.districtId returns only masters in that district, not the rest of the city")
    void should_filterMastersByDistrict_when_districtIdSupplied() throws Exception {
        ensureHttpClient();
        UUID districtA = districtIdInCity("Київ", 0);
        UUID districtB = districtIdInCity("Київ", 1);
        UUID inDistrictA = seedMasterInDistrict("Київ", districtA, "4.30");
        seedMasterInDistrict("Київ", districtB, "4.70");

        log.debug("Act: GET {}?location.cityId=Київ&location.districtId=A — district wins, only district-A master", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ")
                        + "&location.districtId=" + districtA + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("data").size())
                .as("district-primary: a supplied districtId wins over the city")
                .isEqualTo(1);
        assertThat(data.path("data").get(0).path("masterId").asText())
                .isEqualTo(inDistrictA.toString());
        assertThat(data.path("totalElements").asLong()).isEqualTo(1L);
    }

    @Test
    @DisplayName("GET /search/masters — districted city WITHOUT a districtId widens to city-level (both district masters returned)")
    void should_widenToCityLevel_when_districtedCityHasNoDistrictId() throws Exception {
        ensureHttpClient();
        UUID districtA = districtIdInCity("Київ", 0);
        UUID districtB = districtIdInCity("Київ", 1);
        seedMasterInDistrict("Київ", districtA, "4.30");
        seedMasterInDistrict("Київ", districtB, "4.70");

        log.debug("Act: GET {}?location.cityId=Київ (no districtId) — read side widens to whole city", MASTERS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ") + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalElements").asLong())
                .as("a districted city without a district widens to city-level on the read side (write-side is Phase 10.6)")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("GET /search/salons — district-primary: ?location.districtId filters salons by district, not the whole city")
    void should_filterSalonsByDistrict_when_districtIdSupplied() throws Exception {
        ensureHttpClient();
        UUID districtA = districtIdInCity("Київ", 0);
        UUID districtB = districtIdInCity("Київ", 1);
        seedSalonInDistrict("Київ", districtA, "Salon District A");
        seedSalonInDistrict("Київ", districtB, "Salon District B");

        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + cityIdByName("Київ")
                        + "&location.districtId=" + districtA + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode page = objectMapper.readTree(response.getBody()).path("data");
        assertThat(page.path("totalElements").asLong())
                .as("salon search honours the district-primary FK filter")
                .isEqualTo(1L);
        assertThat(page.path("data").get(0).path("name").asText()).isEqualTo("Salon District A");
        assertThat(page.path("data").get(0).path("districtLabel").asText())
                .as("resolved districtLabel is the taxonomy name_uk — no extra round-trip")
                .isNotBlank();
    }

    // ── Phase 10.5 — SALON_MASTER salon-link locality (multi-salon 2.11–2.14) ─

    @Test
    @DisplayName("GET /search/masters — an employed SALON_MASTER is discovered at its SALON's district (salon link resolved at query time, not denormalised)")
    void should_resolveSalonMasterLocalityViaSalonLink_when_searchedByDistrict() throws Exception {
        ensureHttpClient();
        UUID salonDistrict = districtIdInCity("Київ", 0);
        UUID otherDistrict = districtIdInCity("Київ", 1);
        // Master's USER row carries a different (stale) district than its salon.
        // The COALESCE(sal.*, u.*) salon link must win — the master is found
        // under the SALON's district, never the user-row district.
        UUID masterId = seedSalonMasterWithSalonDistrictAndUserDistrict(
                "Київ", salonDistrict, otherDistrict, "4.60");

        log.debug("Act: GET {} filtered by the SALON's district — salon-link locality must win over the user row", MASTERS_URL);
        ResponseEntity<String> bySalonDistrict = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ")
                        + "&location.districtId=" + salonDistrict + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);
        ResponseEntity<String> byUserDistrict = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ")
                        + "&location.districtId=" + otherDistrict + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        JsonNode bySalon = objectMapper.readTree(bySalonDistrict.getBody()).path("data");
        assertThat(bySalon.path("totalElements").asLong())
                .as("SALON_MASTER discovered under its salon's district (link resolved per request)")
                .isEqualTo(1L);
        assertThat(bySalon.path("data").get(0).path("masterId").asText())
                .isEqualTo(masterId.toString());
        assertThat(objectMapper.readTree(byUserDistrict.getBody()).path("data").path("totalElements").asLong())
                .as("the stale user-row district must NOT discover the master — no denormalised address")
                .isZero();
    }

    @Test
    @DisplayName("GET /search/masters — an INDEPENDENT_MASTER (no salon) is discovered via its own user-row district (COALESCE falls through)")
    void should_resolveIndependentMasterLocalityViaUserRow_when_searchedByDistrict() throws Exception {
        ensureHttpClient();
        UUID district = districtIdInCity("Київ", 0);
        UUID masterId = seedIndependentMasterInDistrict("Київ", district, "4.10");

        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ")
                        + "&location.districtId=" + district + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalElements").asLong())
                .as("no salon link → COALESCE falls through to the user row's district")
                .isEqualTo(1L);
        assertThat(data.path("data").get(0).path("masterId").asText()).isEqualTo(masterId.toString());
    }

    // ── Phase 10.5 — resolved labels (no round-trip; null districtLabel for non-districted) ─

    @Test
    @DisplayName("GET /search/masters — district master carries resolved cityLabel AND districtLabel name_uk in one response")
    void should_stampBothLabels_when_masterIsInADistrict() throws Exception {
        ensureHttpClient();
        UUID district = districtIdInCity("Київ", 0);
        seedSalonMasterWithSalonDistrictAndUserDistrict("Київ", district, district, "4.80");

        ResponseEntity<String> response = restTemplate.exchange(
                MASTERS_URL + "?location.cityId=" + cityIdByName("Київ")
                        + "&location.districtId=" + district + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        JsonNode row = objectMapper.readTree(response.getBody()).path("data").path("data").get(0);
        assertThat(row.path("cityLabel").asText())
                .as("cityLabel resolved from taxonomy name_uk with no extra client round-trip")
                .isEqualTo("Київ");
        assertThat(row.path("districtLabel").isNull())
                .as("districted master → districtLabel is the taxonomy name_uk, not null")
                .isFalse();
        assertThat(row.path("districtLabel").asText()).isNotBlank();
        // §I — internal FK UUIDs are not exposed on this permitAll endpoint.
        assertThat(row.path("cityId").isMissingNode())
                .as("internal city UUID must not leak on the public search DTO")
                .isTrue();
        assertThat(row.path("districtId").isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("GET /search/salons — non-districted salon has resolved cityLabel and a null districtLabel")
    void should_returnNullDistrictLabel_when_salonCityIsNotDistricted() throws Exception {
        ensureHttpClient();
        seedActiveSalon("Львів", null);

        ResponseEntity<String> response = restTemplate.exchange(
                SALONS_URL + "?location.cityId=" + cityIdByName("Львів") + "&page=0&size=20",
                HttpMethod.GET, anonymous(), String.class);

        JsonNode row = objectMapper.readTree(response.getBody()).path("data").path("data").get(0);
        assertThat(row.path("cityLabel").asText()).isEqualTo("Львів");
        assertThat(row.path("districtLabel").isNull())
                .as("salon with no district FK → districtLabel surfaces as JSON null")
                .isTrue();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Resolves the Nth Flyway-seeded {@code city_districts.id} of a city by the
     * city's Ukrainian name (V53 KATOTTH seed). Київ has multiple urban
     * districts; the deterministic {@code ORDER BY katotth_code} makes index
     * {@code 0} and {@code 1} stable across runs. Reference data — never
     * deleted by {@code cleanDb()}.
     */
    private UUID districtIdInCity(String cityNameUk, int index) {
        return jdbcTemplate.queryForObject(
                "SELECT cd.id FROM city_districts cd " +
                        "JOIN cities c ON c.id = cd.city_id " +
                        "WHERE c.name_uk = ? ORDER BY cd.katotth_code OFFSET ? LIMIT 1",
                UUID.class, cityNameUk, index);
    }

    /**
     * Seeds a {@code SALON_ADMIN} user with a backing {@code masters} row in
     * the given city. A SALON_ADMIN must never surface in public master
     * discovery regardless of having a masters row — the
     * {@code AND u.role <> 'SALON_ADMIN'} predicate (data + count) is what
     * enforces it.
     */
    private UUID seedSalonAdminMaster(String city, String avgRating) {
        UUID cityId = cityIdByName(city);

        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                ownerId, "admin-owner-" + UUID.randomUUID() + "@beautica.test",
                "$2a$04$placeholdervaluefortestonlydigest");

        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, city, city_id, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "AdminSalon-" + salonId, city, cityId);

        UUID adminUserId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, city, city_id, is_active, email_verified) " +
                        "VALUES (?, ?, ?, 'SALON_ADMIN', ?, ?, ?, true, true)",
                adminUserId, "salon-admin-" + UUID.randomUUID() + "@beautica.test",
                "$2a$04$placeholdervaluefortestonlydigest", salonId, city, cityId);

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'SALON_MASTER', ?::numeric, 1, true, NOW(), NOW())",
                masterId, adminUserId, salonId, avgRating);
        return masterId;
    }

    /**
     * Seeds a SALON_MASTER whose SALON carries {@code district_id}. The
     * master's discovery locality resolves through the salon link
     * ({@code COALESCE(sal.district_id, u.district_id)}) at query time.
     */
    private UUID seedMasterInDistrict(String city, UUID districtId, String avgRating) {
        return seedSalonMasterWithSalonDistrictAndUserDistrict(city, districtId, null, avgRating);
    }

    /**
     * Seeds a SALON_OWNER + a salon stamped with {@code salonDistrictId}, plus
     * an employed SALON_MASTER whose own user row carries
     * {@code userDistrictId} (may differ from the salon's, or be {@code null}).
     * Proves the salon link wins at query time and is never denormalised onto
     * the master/user row.
     */
    private UUID seedSalonMasterWithSalonDistrictAndUserDistrict(
            String city, UUID salonDistrictId, UUID userDistrictId, String avgRating) {
        UUID cityId = cityIdByName(city);

        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                ownerId, "dist-owner-" + UUID.randomUUID() + "@beautica.test",
                "$2a$04$placeholdervaluefortestonlydigest");

        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, city, city_id, district_id, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "DistSalon-" + salonId, city, cityId, salonDistrictId);

        UUID masterUserId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, city, city_id, district_id, is_active, email_verified) " +
                        "VALUES (?, ?, ?, 'SALON_MASTER', ?, ?, ?, ?, true, true)",
                masterUserId, "dist-master-" + UUID.randomUUID() + "@beautica.test",
                "$2a$04$placeholdervaluefortestonlydigest", salonId, city, cityId, userDistrictId);

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'SALON_MASTER', ?::numeric, 1, true, NOW(), NOW())",
                masterId, masterUserId, salonId, avgRating);
        return masterId;
    }

    /**
     * Seeds an INDEPENDENT_MASTER (no {@code salon_id}) whose own user row
     * carries the district FK. With no salon link the
     * {@code COALESCE(sal.district_id, u.district_id)} expression falls through
     * to the user row.
     */
    private UUID seedIndependentMasterInDistrict(String city, UUID districtId, String avgRating) {
        UUID cityId = cityIdByName(city);

        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, city, city_id, district_id, is_active, email_verified) " +
                        "VALUES (?, ?, ?, 'INDEPENDENT_MASTER', ?, ?, ?, true, true)",
                userId, "indep-master-" + UUID.randomUUID() + "@beautica.test",
                "$2a$04$placeholdervaluefortestonlydigest", city, cityId, districtId);

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, 'INDEPENDENT_MASTER', ?::numeric, 1, true, NOW(), NOW())",
                masterId, userId, avgRating);
        return masterId;
    }

    /** Seeds a SALON_OWNER + an active salon stamped with a district FK. */
    private UUID seedSalonInDistrict(String city, UUID districtId, String name) {
        UUID cityId = cityIdByName(city);

        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                ownerId, "dsalon-owner-" + UUID.randomUUID() + "@beautica.test",
                "$2a$04$placeholdervaluefortestonlydigest");

        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, city, city_id, district_id, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, name, city, cityId, districtId);
        return salonId;
    }

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
        UUID cityId = cityIdByName(city);

        UUID ownerId = UUID.randomUUID();
        String ownerEmail = "search-owner-" + UUID.randomUUID() + "@beautica.test";
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                ownerId, ownerEmail, "$2a$04$placeholdervaluefortestonlydigest");

        // Phase 10.5: the salon carries the FK locality. An employed
        // SALON_MASTER's discovery locality resolves through this salon link
        // at query time (COALESCE(sal.city_id, u.city_id)) — never copied onto
        // the master/user row.
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, city, city_id, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + salonId, city, cityId);

        UUID masterUserId = UUID.randomUUID();
        String masterEmail = "search-master-" + UUID.randomUUID() + "@beautica.test";
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, city, is_active, email_verified) " +
                        "VALUES (?, ?, ?, 'SALON_MASTER', ?, ?, true, true)",
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
        UUID cityId = cityIdByName(city);

        UUID ownerId = UUID.randomUUID();
        String ownerEmail = "salon-owner-" + UUID.randomUUID() + "@beautica.test";
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                ownerId, ownerEmail, "$2a$04$placeholdervaluefortestonlydigest");

        // Phase 10.5: salon discovery locality is its own city_id FK (a salon
        // has no salon-to-salon link to resolve through).
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, city, region, city_id, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + salonId, city, region, cityId);
        return salonId;
    }

    /**
     * Resolves a Flyway-seeded {@code cities.id} by its canonical Ukrainian
     * name (V53 KATOTTH seed). The taxonomy is reference data — never deleted
     * by {@code cleanDb()} — so this lookup is stable across tests. Used to
     * drive the Phase 10.5 FK location filter ({@code ?location.cityId=})
     * and to stamp {@code salons.city_id} / wire the SALON_MASTER salon link.
     */
    private UUID cityIdByName(String nameUk) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM cities WHERE name_uk = ? ORDER BY katotth_code LIMIT 1",
                UUID.class, nameUk);
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
